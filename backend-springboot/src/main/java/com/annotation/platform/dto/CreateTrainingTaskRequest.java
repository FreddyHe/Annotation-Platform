package com.annotation.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class CreateTrainingTaskRequest {
    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    @NotBlank(message = "下载命令不能为空")
    private String downloadCommand;

    @Min(value = 1, message = "训练轮数至少为1")
    private Integer epochs = 50;

    private Integer batchSize = 16;
}
