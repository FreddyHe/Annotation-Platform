package com.annotation.platform.dto.request.upload;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadChunkRequest {

    @NotNull(message = "文件ID不能为空")
    private String fileId;

    @NotNull(message = "文件名不能为空")
    private String filename;

    @NotNull(message = "分块索引不能为空")
    private Integer chunkIndex;

    @NotNull(message = "总分块数不能为空")
    private Integer totalChunks;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;
}
