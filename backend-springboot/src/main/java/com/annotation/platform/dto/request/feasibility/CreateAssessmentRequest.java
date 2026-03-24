package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotBlank;
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
}