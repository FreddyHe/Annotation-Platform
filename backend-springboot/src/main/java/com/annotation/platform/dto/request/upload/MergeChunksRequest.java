package com.annotation.platform.dto.request.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MergeChunksRequest {

    @NotBlank(message = "文件ID不能为空")
    private String fileId;

    @NotBlank(message = "文件名不能为空")
    private String filename;

    @NotNull(message = "总分块数不能为空")
    private Integer totalChunks;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;
}
