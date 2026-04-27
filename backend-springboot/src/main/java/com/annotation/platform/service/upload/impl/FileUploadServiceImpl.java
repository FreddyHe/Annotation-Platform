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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
    private final ObjectMapper objectMapper;

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
            if (chunkFile.exists() && !chunkFile.delete()) {
                throw new IOException("无法覆盖已存在分块: " + chunkFile.getName());
            }
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
            progress.setFileSize(request.getFileSize());
            progress.setProjectId(request.getProjectId());

            if (progress.getReceivedChunks().size() == totalChunks) {
                progress.setStatus("ready_to_merge");
                progress.setProgress(100);
            } else {
                progress.setProgress((int) ((double) progress.getReceivedChunks().size() / totalChunks * 100));
            }

            log.info("分块上传成功: fileId={}, chunkIndex={}/{}, progress={}%", 
                    fileId, chunkIndex, totalChunks, progress.getProgress());

            writeManifest(progress);

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

        File chunkDir = new File(chunkPath, fileId);
        Set<Integer> diskChunks = scanUploadedChunkIndexes(chunkDir, filename, totalChunks);
        if (diskChunks.size() != totalChunks) {
            throw new BusinessException(ErrorCode.FILE_006, 
                    String.format("分块不完整: 已接收 %d/%d", diskChunks.size(), totalChunks));
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

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
            Map<String, Object> chunks = listUploadedChunks(fileId);
            @SuppressWarnings("unchecked")
            List<Integer> uploadedChunks = (List<Integer>) chunks.get("uploadedChunks");
            if (uploadedChunks == null || uploadedChunks.isEmpty()) {
                throw new BusinessException(ErrorCode.FILE_001, "上传进度不存在");
            }
            String filename = (String) chunks.get("filename");
            Integer totalChunks = (Integer) chunks.get("totalChunks");
            int progressValue = totalChunks != null && totalChunks > 0
                    ? (int) ((double) uploadedChunks.size() / totalChunks * 100)
                    : 0;
            return UploadProgressResponse.builder()
                    .fileId(fileId)
                    .filename(filename)
                    .totalChunks(totalChunks)
                    .receivedChunks(uploadedChunks.size())
                    .progress(progressValue)
                    .status("uploading")
                    .build();
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
    public Map<String, Object> listUploadedChunks(String fileId) {
        File chunkDir = new File(chunkPath, fileId);
        Map<String, Object> manifest = readManifest(chunkDir);
        String filename = manifest.get("filename") instanceof String ? (String) manifest.get("filename") : null;
        Integer totalChunks = manifest.get("totalChunks") instanceof Number
                ? ((Number) manifest.get("totalChunks")).intValue()
                : null;

        List<Integer> uploadedChunks = new ArrayList<>();
        if (filename != null && totalChunks != null) {
            uploadedChunks.addAll(scanUploadedChunkIndexes(chunkDir, filename, totalChunks));
        } else if (chunkDir.exists()) {
            File[] files = chunkDir.listFiles(file -> file.isFile() && !"manifest.json".equals(file.getName()));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    int idx = name.lastIndexOf('_');
                    if (idx >= 0 && idx < name.length() - 1) {
                        try {
                            uploadedChunks.add(Integer.parseInt(name.substring(idx + 1)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        Collections.sort(uploadedChunks);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", fileId);
        response.put("filename", filename);
        response.put("projectId", manifest.get("projectId"));
        response.put("totalChunks", totalChunks);
        response.put("fileSize", manifest.get("fileSize"));
        response.put("uploadedChunks", uploadedChunks);
        response.put("receivedChunks", uploadedChunks.size());
        response.put("createdAt", manifest.get("createdAt"));
        response.put("updatedAt", manifest.get("updatedAt"));
        return response;
    }

    @PostConstruct
    public void restoreUploadSessions() {
        File chunkRootDir = new File(chunkPath);
        File[] dirs = chunkRootDir.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            Map<String, Object> manifest = readManifest(dir);
            if (manifest.isEmpty()) {
                continue;
            }
            String fileId = (String) manifest.get("fileId");
            String filename = (String) manifest.get("filename");
            Integer totalChunks = manifest.get("totalChunks") instanceof Number
                    ? ((Number) manifest.get("totalChunks")).intValue()
                    : 0;
            if (fileId == null || filename == null || totalChunks == null || totalChunks <= 0) {
                continue;
            }
            Set<Integer> chunks = scanUploadedChunkIndexes(dir, filename, totalChunks);
            UploadProgress progress = UploadProgress.builder()
                    .fileId(fileId)
                    .filename(filename)
                    .totalChunks(totalChunks)
                    .receivedChunks(chunks)
                    .progress((int) ((double) chunks.size() / totalChunks * 100))
                    .status(chunks.size() == totalChunks ? "ready_to_merge" : "uploading")
                    .createdAt(LocalDateTime.now())
                    .lastUpdated(LocalDateTime.now())
                    .fileSize(manifest.get("fileSize") instanceof Number ? ((Number) manifest.get("fileSize")).longValue() : null)
                    .projectId(manifest.get("projectId") instanceof Number ? ((Number) manifest.get("projectId")).longValue() : null)
                    .build();
            uploadProgressMap.put(fileId, progress);
        }
        log.info("恢复上传会话完成: count={}", uploadProgressMap.size());
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

    private Set<Integer> scanUploadedChunkIndexes(File chunkDir, String filename, Integer totalChunks) {
        Set<Integer> chunks = new HashSet<>();
        if (!chunkDir.exists()) {
            return chunks;
        }
        for (int i = 0; i < totalChunks; i++) {
            File chunkFile = new File(chunkDir, String.format("%s_%d", filename, i));
            if (chunkFile.exists() && chunkFile.isFile()) {
                chunks.add(i);
            }
        }
        return chunks;
    }

    private void writeManifest(UploadProgress progress) throws IOException {
        File chunkDir = new File(chunkPath, progress.getFileId());
        if (!chunkDir.exists()) {
            chunkDir.mkdirs();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("fileId", progress.getFileId());
        manifest.put("filename", progress.getFilename());
        manifest.put("projectId", progress.getProjectId());
        manifest.put("totalChunks", progress.getTotalChunks());
        manifest.put("fileSize", progress.getFileSize());
        List<Integer> uploadedChunks = new ArrayList<>(progress.getReceivedChunks());
        Collections.sort(uploadedChunks);
        manifest.put("uploadedChunks", uploadedChunks);
        manifest.put("createdAt", progress.getCreatedAt() != null ? progress.getCreatedAt().toString() : LocalDateTime.now().toString());
        manifest.put("updatedAt", LocalDateTime.now().toString());
        objectMapper.writeValue(new File(chunkDir, "manifest.json"), manifest);
    }

    private Map<String, Object> readManifest(File chunkDir) {
        File manifestFile = new File(chunkDir, "manifest.json");
        if (!manifestFile.exists()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(manifestFile, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("读取上传 manifest 失败: {}", manifestFile.getAbsolutePath(), e);
            return new HashMap<>();
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
        private Long fileSize;
        private Long projectId;
    }
}
