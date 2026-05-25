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

            String fileId = sanitizeFileId(request.getFileId());
            String filename = sanitizeFilename(request.getFilename());
            Integer chunkIndex = request.getChunkIndex();
            Integer totalChunks = request.getTotalChunks();

            Path chunkRoot = Paths.get(chunkPath).toAbsolutePath().normalize();
            Path chunkDirPath = resolveInside(chunkRoot, fileId);
            Files.createDirectories(chunkDirPath);

            File chunkFile = resolveInside(chunkDirPath, String.format("%s_%d", filename, chunkIndex)).toFile();
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
        String fileId = sanitizeFileId(request.getFileId());
        String filename = sanitizeFilename(request.getFilename());
        Integer totalChunks = request.getTotalChunks();
        Long projectId = request.getProjectId();

        File chunkDir = resolveInside(Paths.get(chunkPath).toAbsolutePath().normalize(), fileId).toFile();
        Set<Integer> diskChunks = scanUploadedChunkIndexes(chunkDir, filename, totalChunks);
        if (diskChunks.size() != totalChunks) {
            throw new BusinessException(ErrorCode.FILE_006, 
                    String.format("分块不完整: 已接收 %d/%d", diskChunks.size(), totalChunks));
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));

        Path uploadRoot = Paths.get(basePath).toAbsolutePath().normalize();
        Path projectDirPath = resolveInside(uploadRoot, String.valueOf(projectId));
        File projectDir = projectDirPath.toFile();
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            throw new BusinessException(ErrorCode.FILE_006, "项目目录创建失败");
        }

        File mergedFile = resolveInside(projectDirPath, filename).toFile();

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

            long totalImages = projectImageRepository.countByProjectId(projectId);
            project.setTotalImages(Math.toIntExact(Math.min(Integer.MAX_VALUE, totalImages)));
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
        fileId = sanitizeFileId(fileId);
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
        fileId = sanitizeFileId(fileId);
        File chunkDir = resolveInside(Paths.get(chunkPath).toAbsolutePath().normalize(), fileId).toFile();
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
            try {
                fileId = sanitizeFileId(fileId);
                filename = sanitizeFilename(filename);
            } catch (BusinessException e) {
                log.warn("跳过非法上传会话: dir={}, error={}", dir.getName(), e.getMessage());
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
            Path path = resolveRelativeUploadPath(filePath);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("删除文件失败: {}", e.getMessage(), e);
            return false;
        } catch (BusinessException e) {
            log.warn("拒绝非法文件删除: filePath={}, error={}", filePath, e.getMessage());
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

    private String sanitizeFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_004, "文件ID为空");
        }
        String safe = fileId.trim();
        if (!safe.matches("[a-zA-Z0-9._-]{1,200}") || ".".equals(safe) || "..".equals(safe)) {
            throw new BusinessException(ErrorCode.FILE_004, "文件ID非法");
        }
        return safe;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_004, "文件名为空");
        }
        String normalized = filename.replace('\\', '/');
        String baseName = Paths.get(normalized).getFileName().toString();
        String safe = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            throw new BusinessException(ErrorCode.FILE_004, "文件名非法");
        }
        return safe;
    }

    private Path resolveInside(Path root, String child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(child).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new BusinessException(ErrorCode.FILE_004, "文件路径非法");
        }
        return target;
    }

    private Path resolveRelativeUploadPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_004, "文件路径为空");
        }
        Path relative = Paths.get(filePath.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() < 2) {
            throw new BusinessException(ErrorCode.FILE_004, "文件路径非法");
        }
        Path uploadRoot = Paths.get(basePath).toAbsolutePath().normalize();
        Path target = uploadRoot.resolve(relative).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new BusinessException(ErrorCode.FILE_004, "文件路径非法");
        }
        return target;
    }

    private String uniqueFilename(Path directory, String safeFilename) {
        Path target = resolveInside(directory, safeFilename);
        if (!Files.exists(target)) {
            return safeFilename;
        }
        int dotIndex = safeFilename.lastIndexOf('.');
        String name = dotIndex > 0 ? safeFilename.substring(0, dotIndex) : safeFilename;
        String ext = dotIndex > 0 ? safeFilename.substring(dotIndex) : "";
        for (int i = 1; i <= 1000; i++) {
            String candidate = name + "_" + i + ext;
            if (!Files.exists(resolveInside(directory, candidate))) {
                return candidate;
            }
        }
        return UUID.randomUUID() + "_" + safeFilename;
    }

    private void validateChunkRequest(UploadChunkRequest request, MultipartFile file) {
        if (request.getFileId() == null || request.getFilename() == null
                || request.getChunkIndex() == null || request.getTotalChunks() == null
                || request.getFileSize() == null || request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传参数不完整");
        }

        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_002, "分块文件为空");
        }

        if (file.getSize() > chunkSize) {
            throw new BusinessException(ErrorCode.FILE_003, "分块大小超限");
        }

        if (request.getTotalChunks() <= 0 || request.getChunkIndex() < 0 || request.getChunkIndex() >= request.getTotalChunks()) {
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
                File chunkFile = resolveInside(chunkDir.toPath(), String.format("%s_%d", filename, i)).toFile();
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
            File chunkFile = resolveInside(chunkDir.toPath(), String.format("%s_%d", filename, i)).toFile();
            if (chunkFile.exists() && chunkFile.isFile()) {
                chunks.add(i);
            }
        }
        return chunks;
    }

    private void writeManifest(UploadProgress progress) throws IOException {
        File chunkDir = resolveInside(Paths.get(chunkPath).toAbsolutePath().normalize(), progress.getFileId()).toFile();
        if (!chunkDir.exists() && !chunkDir.mkdirs()) {
            throw new IOException("无法创建分块目录: " + chunkDir.getAbsolutePath());
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
                
                String safeEntryName = sanitizeFilename(entryName);
                String outputName = uniqueFilename(projectDir.toPath(), safeEntryName);
                File outputFile = resolveInside(projectDir.toPath(), outputName).toFile();
                
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
                        .status(ProjectImage.ImageStatus.COMPLETED)
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
