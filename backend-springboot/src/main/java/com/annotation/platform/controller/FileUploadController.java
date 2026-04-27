package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.upload.MergeChunksRequest;
import com.annotation.platform.dto.request.upload.UploadChunkRequest;
import com.annotation.platform.dto.response.upload.UploadProgressResponse;
import com.annotation.platform.service.upload.FileUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;

    @Value("${app.file.upload.base-path}")
    private String basePath;

    @PostMapping("/chunk")
    public Result<String> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileId") String fileId,
            @RequestParam("filename") String filename,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("totalChunks") Integer totalChunks,
            @RequestParam("fileSize") Long fileSize,
            @RequestParam("projectId") Long projectId) {

        log.info("接收分块: fileId={}, chunkIndex={}/{}, filename={}", fileId, chunkIndex, totalChunks, filename);

        UploadChunkRequest request = UploadChunkRequest.builder()
                .fileId(fileId)
                .filename(filename)
                .chunkIndex(chunkIndex)
                .totalChunks(totalChunks)
                .fileSize(fileSize)
                .projectId(projectId)
                .build();

        String uploadedFileId = fileUploadService.uploadChunk(request, file);
        return Result.success(uploadedFileId);
    }

    @PostMapping("/merge")
    public Result<String> mergeChunks(@Valid @RequestBody MergeChunksRequest request) {
        log.info("合并分块: fileId={}, filename={}", request.getFileId(), request.getFilename());
        String filePath = fileUploadService.mergeChunks(request);
        return Result.success(filePath);
    }

    @GetMapping("/progress/{fileId}")
    public Result<UploadProgressResponse> getUploadProgress(@PathVariable String fileId) {
        UploadProgressResponse progress = fileUploadService.getUploadProgress(fileId);
        return Result.success(progress);
    }

    @GetMapping("/chunks/{fileId}")
    public Result<Map<String, Object>> getUploadedChunks(@PathVariable String fileId) {
        return Result.success(fileUploadService.listUploadedChunks(fileId));
    }

    @DeleteMapping("/file")
    public Result<Void> deleteFile(@RequestParam String filePath) {
        log.info("删除文件: {}", filePath);
        boolean deleted = fileUploadService.deleteFile(filePath);
        return deleted ? Result.success() : Result.error("删除失败");
    }

    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("接收图片上传: filename={}, size={}", file.getOriginalFilename(), file.getSize());
        String filePath = fileUploadService.uploadSingleImage(file);
        Map<String, String> result = new HashMap<>();
        result.put("url", filePath);
        result.put("path", filePath);
        return Result.success(result);
    }

    @PostMapping("/training-dataset")
    public Result<Map<String, String>> uploadTrainingDataset(@RequestParam("file") MultipartFile file) {
        log.info("接收训练数据集上传: filename={}, size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) {
            return Result.error("400", "文件为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            return Result.error("400", "训练数据集目前仅支持 ZIP 压缩包");
        }

        try {
            Path uploadDir = Paths.get(basePath, "custom_model_datasets");
            Files.createDirectories(uploadDir);
            String safeName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
            Path target = uploadDir.resolve(filename);
            file.transferTo(target.toFile());

            Map<String, String> result = new HashMap<>();
            result.put("path", "custom_model_datasets/" + filename);
            result.put("absolutePath", target.toAbsolutePath().toString());
            result.put("filename", filename);
            return Result.success(result);
        } catch (Exception e) {
            log.error("训练数据集上传失败", e);
            return Result.error("500", "训练数据集上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/view")
    public void viewFile(@RequestParam String path, jakarta.servlet.http.HttpServletResponse response) {
        log.info("查看文件: {}", path);
        try {
            File file = new File(basePath, path);
            
            if (!file.exists() || !file.isFile()) {
                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            
            String contentType = java.nio.file.Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            
            response.setContentType(contentType);
            response.setContentLength((int) file.length());
            
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                 java.io.OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }
        } catch (Exception e) {
            log.error("查看文件失败: {}", path, e);
            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
