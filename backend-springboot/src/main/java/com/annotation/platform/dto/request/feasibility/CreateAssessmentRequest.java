package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateAssessmentRequest {

    @NotBlank(message = "评估名称不能为空")
    @Size(min = 3, max = 200, message = "评估名称长度必须在3-200个字符之间")
    private String assessmentName;

    @NotBlank(message = "原始需求不能为空")
    private String rawRequirement;

    private List<String> imageUrls;

    @Min(value = 0, message = "数据量不能为负数")
    private Integer datasetSize;

    @Min(value = 1, message = "类别数量至少为1")
    @Max(value = 200, message = "类别数量不能超过200")
    private Integer categoryCount;

    @Min(value = 0, message = "单类别样本数不能为负数")
    private Integer samplesPerCategory;

    @Size(max = 50, message = "图片质量不能超过50个字符")
    private String imageQuality;

    @Min(value = 0, message = "标注完整度不能低于0")
    @Max(value = 100, message = "标注完整度不能超过100")
    private Integer annotationCompleteness;

    @Size(max = 50, message = "目标尺寸不能超过50个字符")
    private String targetSize;

    @Size(max = 50, message = "背景复杂度不能超过50个字符")
    private String backgroundComplexity;

    @Size(max = 50, message = "类间相似度不能超过50个字符")
    private String interClassSimilarity;

    @Min(value = 1, message = "预期精度至少为1")
    @Max(value = 100, message = "预期精度不能超过100")
    private Integer expectedAccuracy;

    @Size(max = 100, message = "训练资源不能超过100个字符")
    private String trainingResource;

    @Min(value = 1, message = "时间预算至少为1天")
    private Integer timeBudgetDays;
}
