package com.annotation.platform.service.upload;

import com.annotation.platform.dto.request.upload.MergeChunksRequest;
import com.annotation.platform.dto.request.upload.UploadChunkRequest;
import com.annotation.platform.dto.response.upload.UploadProgressResponse;
import org.springframework.web.multipart.MultipartFile;

public interface FileUploadService {

    String uploadChunk(UploadChunkRequest request, MultipartFile file);

    String mergeChunks(MergeChunksRequest request);

    UploadProgressResponse getUploadProgress(String fileId);

    boolean deleteFile(String filePath);

    void cleanupExpiredChunks();

    String uploadSingleImage(MultipartFile file);
}
