package com.annotation.platform.dto.response.feasibility;

import com.annotation.platform.entity.AssessmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeasibilityAssessmentResponse {

    private Long id;
    private String assessmentName;
    private String rawRequirement;
    private String structuredRequirement;
    private AssessmentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private Long createdBy;
    private String createdByUsername;
    private Long organizationId;
    private String organizationName;
}