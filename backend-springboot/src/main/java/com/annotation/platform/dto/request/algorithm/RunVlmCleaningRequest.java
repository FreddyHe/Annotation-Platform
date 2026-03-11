package com.annotation.platform.dto.request.algorithm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RunVlmCleaningRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotBlank(message = "模型名称不能为空")
    private String model = "Qwen3-VL-4B-Instruct";

    private Integer maxTokens = 4096;

    private Integer minDim = 10;
}
