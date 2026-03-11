package com.annotation.platform.dto.response.upload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadProgressResponse {

    private String fileId;
    private String filename;
    private Integer totalChunks;
    private Integer receivedChunks;
    private Integer progress;
    private String status;
}
