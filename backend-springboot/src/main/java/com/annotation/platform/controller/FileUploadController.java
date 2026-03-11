package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.upload.MergeChunksRequest;
import com.annotation.platform.dto.request.upload.UploadChunkRequest;
import com.annotation.platform.dto.response.upload.UploadProgressResponse;
import com.annotation.platform.service.upload.FileUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;

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

    @DeleteMapping("/file")
    public Result<Void> deleteFile(@RequestParam String filePath) {
        log.info("删除文件: {}", filePath);
        boolean deleted = fileUploadService.deleteFile(filePath);
        return deleted ? Result.success() : Result.error("删除失败");
    }
}
