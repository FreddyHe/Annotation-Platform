package com.annotation.platform.dto.request.feasibility;

import com.annotation.platform.entity.FeasibilityBucket;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateResourceEstimationRequest {

    @NotBlank(message = "检测类别不能为空")
    @Size(max = 200, message = "检测类别长度不能超过200个字符")
    private String categoryName;

    @NotNull(message = "可行性路径不能为空")
    private FeasibilityBucket feasibilityBucket;

    private Integer estimatedImages;

    private Integer estimatedManDays;

    private Integer gpuHours;

    private Integer iterationCount;

    private Integer estimatedTotalDays;

    private Double estimatedCost;

    private String notes;
}
