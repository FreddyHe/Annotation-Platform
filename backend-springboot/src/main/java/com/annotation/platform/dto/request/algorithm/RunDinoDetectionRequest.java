package com.annotation.platform.dto.request.algorithm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RunDinoDetectionRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "标签列表不能为空")
    private List<String> labels;

    private Double boxThreshold = 0.3;

    private Double textThreshold = 0.25;
}
