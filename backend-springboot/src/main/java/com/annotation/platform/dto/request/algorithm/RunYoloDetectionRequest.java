package com.annotation.platform.dto.request.algorithm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RunYoloDetectionRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "标签列表不能为空")
    private List<String> labels;

    private String modelSize = "n";

    private Double confThreshold = 0.25;

    private Double iouThreshold = 0.45;
}
