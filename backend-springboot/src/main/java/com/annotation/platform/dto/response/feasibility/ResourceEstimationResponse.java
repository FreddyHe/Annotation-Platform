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
public class ResourceEstimationResponse {

    private Long id;
    private Long assessmentId;
    private String categoryName;
    private FeasibilityBucket feasibilityBucket;
    private Integer estimatedImages;
    private Integer estimatedManDays;
    private Integer gpuHours;
    private Integer iterationCount;
    private Integer estimatedTotalDays;
    private Double estimatedCost;
    private String notes;
    private LocalDateTime createdAt;
}
