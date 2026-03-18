package com.annotation.platform.dto.response.feasibility;

import com.annotation.platform.entity.FeasibilityBucket;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryAssessmentResponse {

    private Long id;
    private Long assessmentId;
    private String categoryName;
    private String categoryNameEn;
    private String categoryType;
    private String sceneDescription;
    private String viewAngle;
    private String environmentConstraints;
    private String performanceRequirements;
    private FeasibilityBucket feasibilityBucket;
    private Double confidence;
    private String reasoning;
    private LocalDateTime createdAt;
}
