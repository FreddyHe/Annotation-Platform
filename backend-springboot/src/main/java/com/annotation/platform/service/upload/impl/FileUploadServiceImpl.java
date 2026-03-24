package com.annotation.platform.service.upload.impl;

import com.annotation.platform.common.ErrorCode;
import com.annotation.platform.dto.request.upload.MergeChunksRequest;
import com.annotation.platform.dto.request.upload.UploadChunkRequest;
import com.annotation.platform.dto.response.upload.UploadProgressResponse;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.ProjectImage;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.ProjectImageRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.upload.FileUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    @Value("${app.file.upload.base-path}")
    private String basePath;

    @Value("${app.file.upload.chunk-path}")
    private String chunkPath;

    @Value("${app.file.upload.chunk-size}")
    private Long chunkSize;

    private final ProjectRepository projectRepository;
    private final ProjectImageRepository projectImageRepository;

    private final Map<String, UploadProgress> uploadProgressMap = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public String uploadChunk(UploadChunkRequest request, MultipartFile file) {
        try {
            validateChunkRequest(request, file);

            String fileId = request.getFileId();
            String filename = request.getFilename();
            Integer chunkIndex = request.getChunkIndex();
            Integer totalChunks = request.getTotalChunks();

            File chunkDir = new File(chunkPath, fileId);
            if (!chunkDir.exists()) {
                chunkDir.mkdirs();
            }

            File chunkFile = new File(chunkDir, String.format("%s_%d", filename, chunkIndex));
            file.transferTo(chunkFile);

            UploadProgress progress = uploadProgressMap.computeIfAbsent(fileId, k -> 
                    UploadProgress.builder()
                            .fileId(fileId)
                            .filename(filename)
                            .totalChunks(totalChunks)
                            .receivedChunks(new HashSet<>())
                            .status("uploading")
                            .createdAt(LocalDateTime.now())
                            .build()
            );

            progress.getReceivedChunks().add(chunkIndex);
            progress.setLastUpdated(LocalDateTime.now());

            if (progress.getReceivedChunks().size() == totalChunks) {
                progress.setStatus("ready_to_merge");
                progress.setProgress(100);
            } else {
                progress.setProgress((int) ((double) progress.getReceivedChunks().size() / totalChunks * 100));
            }

            log.info("分块上传成功: fileId={}, chunkIndex={}/{}, progress={}%", 
                    fileId, chunkIndex, totalChunks, progress.getProgress());

            return fileId;

        } catch (IOException e) {
            log.error("分块上传失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.FILE_005);
        }
    }

    @Override
    @Transactional
    public String mergeChunks(MergeChunksRequest request) {
        String fileId = request.getFileId();
        String filename = request.getFilename();
        Integer totalChunks = request.getTotalChunks();
        Long projectId = request.getProjectId();

        UploadProgress progress = uploadProgressMap.get(fileId);
        if (progress == null) {
            throw new BusinessException(ErrorCode.FILE_006, "上传进度不存在");
        }

        if (progress.getReceivedChunks().size() != totalChunks) {
            throw new BusinessException(ErrorCode.FILE_006, 
                    String.format("分块不完整: 已接收 %d/%d", progress.getReceivedChunks().size(), totalChunks));
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

        File chunkDir = new File(chunkPath, fileId);
        File projectDir = new File(basePath, String.valueOf(projectId));
        if (!projectDir.exists()) {
            projectDir.mkdirs();
        }

        File mergedFile = new File(projectDir, filename);

        try {
            mergeFileChunks(chunkDir, filename, totalChunks, mergedFile);

            List<String> imagePaths = new ArrayList<>();

            if (filename.toLowerCase().endsWith(".zip")) {
                imagePaths = extractImagesFromZip(mergedFile, projectDir, project);
            } else if (isImageFile(filename)) {
                String relativePath = String.format("%d/%s", projectId, filename);
                ProjectImage projectImage = ProjectImage.builder()
                        .project(project)
                        .fileName(filename)
                        .filePath(relativePath)
                        .fileSize(mergedFile.length())
                        .status(ProjectImage.ImageStatus.COMPLETED)
                        .uploadedAt(LocalDateTime.now())
                        .build();
                projectImageRepository.save(projectImage);
                imagePaths.add(relativePath);
            } else {
                log.warn("不支持的文件类型: {}", filename);
            }

            project.setTotalImages(project.getTotalImages() + imagePaths.size());
            projectRepository.save(project);

            uploadProgressMap.remove(fileId);

            deleteDirectory(chunkDir);

            log.info("文件处理成功: fileId={}, filename={}, images={}", fileId, filename, imagePaths.size());

            return imagePaths.isEmpty() ? String.format("%d/%s", projectId, filename) : imagePaths.get(0);

        } catch (IOException e) {
            log.error("文件处理失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.FILE_006, "文件处理失败: " + e.getMessage());
        }
    }

    @Override
    public UploadProgressResponse getUploadProgress(String fileId) {
        UploadProgress progress = uploadProgressMap.get(fileId);
        if (progress == null) {
            throw new BusinessException(ErrorCode.FILE_001, "上传进度不存在");
        }

        return UploadProgressResponse.builder()
                .fileId(progress.getFileId())
                .filename(progress.getFilename())
                .totalChunks(progress.getTotalChunks())
                .receivedChunks(progress.getReceivedChunks().size())
                .progress(progress.getProgress())
                .status(progress.getStatus())
                .build();
    }

    @Override
    public boolean deleteFile(String filePath) {
        try {
            Path path = Paths.get(basePath, filePath);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("删除文件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void cleanupExpiredChunks() {
        File chunkRootDir = new File(chunkPath);
        if (!chunkRootDir.exists()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long expirationTime = 24 * 60 * 60 * 1000;

        File[] fileDirs = chunkRootDir.listFiles(File::isDirectory);
        if (fileDirs != null) {
            for (File fileDir : fileDirs) {
                UploadProgress progress = uploadProgressMap.get(fileDir.getName());
                if (progress == null || 
                    (currentTime - progress.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) > expirationTime) {
                    deleteDirectory(fileDir);
                    uploadProgressMap.remove(fileDir.getName());
                    log.info("清理过期分块: {}", fileDir.getName());
                }
            }
        }
    }

    private void validateChunkRequest(UploadChunkRequest request, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_002, "分块文件为空");
        }

        if (file.getSize() > chunkSize) {
            throw new BusinessException(ErrorCode.FILE_003, "分块大小超限");
        }

        if (request.getChunkIndex() < 0 || request.getChunkIndex() >= request.getTotalChunks()) {
            throw new BusinessException(ErrorCode.FILE_005, "分块索引无效");
        }

        if (request.getFileSize() > 5L * 1024 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.FILE_003, "文件大小超限");
        }
    }

    private void mergeFileChunks(File chunkDir, String filename, Integer totalChunks, File mergedFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(mergedFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            for (int i = 0; i < totalChunks; i++) {
                File chunkFile = new File(chunkDir, String.format("%s_%d", filename, i));
                if (!chunkFile.exists()) {
                    throw new IOException(String.format("分块文件不存在: %s_%d", filename, i));
                }

                try (FileInputStream fis = new FileInputStream(chunkFile);
                     BufferedInputStream bis = new BufferedInputStream(fis)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, bytesRead);
                    }
                }
            }
        }
    }

    private boolean deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return directory.delete();
    }

    private List<String> extractImagesFromZip(File zipFile, File projectDir, Project project) throws IOException {
        List<String> imagePaths = new ArrayList<>();
        
        try (java.util.zip.ZipFile zipFileObj = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFileObj.entries();
            
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                
                if (entry.isDirectory()) {
                    continue;
                }
                
                if (!isImageFile(entryName)) {
                    continue;
                }
                
                File outputFile = new File(projectDir, new File(entryName).getName());
                
                try (InputStream is = zipFileObj.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                
                String relativePath = String.format("%d/%s", project.getId(), outputFile.getName());
                ProjectImage projectImage = ProjectImage.builder()
                        .project(project)
                        .fileName(outputFile.getName())
                        .filePath(relativePath)
                        .fileSize(outputFile.length())
                        .status(ProjectImage.ImageStatus.PENDING)
                        .uploadedAt(LocalDateTime.now())
                        .build();
                projectImageRepository.save(projectImage);
                imagePaths.add(relativePath);
                
                log.info("提取图片: {}", entryName);
            }
        }
        
        zipFile.delete();
        
        return imagePaths;
    }

    private boolean isImageFile(String filename) {
        String lowerName = filename.toLowerCase();
        return lowerName.endsWith(".jpg") || 
               lowerName.endsWith(".jpeg") || 
               lowerName.endsWith(".png") || 
               lowerName.endsWith(".bmp") || 
               lowerName.endsWith(".gif") || 
               lowerName.endsWith(".webp");
    }

    @Override
    public String uploadSingleImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_002, "文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isImageFile(originalFilename)) {
            throw new BusinessException(ErrorCode.FILE_004, "只支持图片文件");
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.FILE_003, "图片大小不能超过5MB");
        }

        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String newFilename = timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;

            File uploadDir = new File(basePath, "feasibility");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            File destFile = new File(uploadDir, newFilename);
            file.transferTo(destFile);

            String relativePath = "feasibility/" + newFilename;
            log.info("单文件上传成功: {}", relativePath);

            return relativePath;

        } catch (IOException e) {
            log.error("单文件上传失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.FILE_005, "文件上传失败: " + e.getMessage());
        }
    }

    @lombok.Builder
    @lombok.Data
    private static class UploadProgress {
        private String fileId;
        private String filename;
        private Integer totalChunks;
        private Set<Integer> receivedChunks;
        private Integer progress;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime lastUpdated;
    }
}
