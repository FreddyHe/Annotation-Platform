package com.annotation.platform.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTrainingTaskRequest {
    @Size(max = 200, message = "模型名称不能超过200个字符")
    private String modelName;

    private Long projectId;

    @Size(max = 120, message = "目标类别不能超过120个字符")
    private String targetClassName;

    @Size(max = 50, message = "数据来源不能超过50个字符")
    private String datasetSource = "ROBOFLOW";

    @Size(max = 2000, message = "下载命令不能超过2000个字符")
    private String downloadCommand;

    @Size(max = 1000, message = "数据集地址不能超过1000个字符")
    private String datasetUri;

    @Min(value = 1, message = "训练轮数至少为1")
    @Max(value = 500, message = "训练轮数不能超过500")
    private Integer epochs = 50;

    @Min(value = 1, message = "Batch Size至少为1")
    @Max(value = 128, message = "Batch Size不能超过128")
    private Integer batchSize = 16;

    @Min(value = 320, message = "图片尺寸至少为320")
    @Max(value = 1280, message = "图片尺寸不能超过1280")
    private Integer imageSize = 640;

    @DecimalMin(value = "0.00001", message = "学习率过小")
    @DecimalMax(value = "1.0", message = "学习率不能超过1.0")
    private Double learningRate = 0.01;

    private Boolean usePretrained = true;

    private Boolean automl = true;
}
