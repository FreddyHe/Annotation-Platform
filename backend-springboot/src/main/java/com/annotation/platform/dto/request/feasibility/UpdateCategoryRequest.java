package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateCategoryRequest {

    @Size(max = 200, message = "类别名称长度不能超过200个字符")
    private String categoryName;

    @Size(max = 200, message = "英文类别名称长度不能超过200个字符")
    private String categoryNameEn;

    @Size(max = 50, message = "类别类型长度不能超过50个字符")
    private String categoryType;

    private String sceneDescription;

    @Size(max = 100, message = "视角长度不能超过100个字符")
    private String viewAngle;

    private String environmentConstraints;

    private String performanceRequirements;
}
