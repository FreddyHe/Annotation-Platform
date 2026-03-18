package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateVlmQualityScoreRequest {

    private Integer totalGtEstimated;
    private Integer detected;
    private Integer falsePositive;
    private Double precisionEstimate;
    private Double recallEstimate;

    @Size(max = 20, message = "bbox质量长度不能超过20个字符")
    private String bboxQuality;

    @Size(max = 50, message = "总体结论长度不能超过50个字符")
    private String overallVerdict;

    private String notes;
}
