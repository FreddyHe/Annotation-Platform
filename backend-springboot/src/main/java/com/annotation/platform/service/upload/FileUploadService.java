package com.annotation.platform.service.upload;

import com.annotation.platform.dto.request.upload.MergeChunksRequest;
import com.annotation.platform.dto.request.upload.UploadChunkRequest;
import com.annotation.platform.dto.response.upload.UploadProgressResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface FileUploadService {

    String uploadChunk(UploadChunkRequest request, MultipartFile file);

    String mergeChunks(MergeChunksRequest request);

    UploadProgressResponse getUploadProgress(String fileId);

    Map<String, Object> listUploadedChunks(String fileId);

    boolean deleteFile(String filePath);

    void cleanupExpiredChunks();

    String uploadSingleImage(MultipartFile file);
}
