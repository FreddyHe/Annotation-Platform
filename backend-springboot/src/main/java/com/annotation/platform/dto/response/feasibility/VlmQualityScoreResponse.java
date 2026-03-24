package com.annotation.platform.dto.response.feasibility;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmQualityScoreResponse {

    private Long id;
    private Long ovdTestResultId;
    private Integer totalGtEstimated;
    private Integer detected;
    private Integer falsePositive;
    private Double precisionEstimate;
    private Double recallEstimate;
    private String bboxQuality;
    private String overallVerdict;
    private String notes;
    private LocalDateTime createdAt;
    private java.util.List<VlmEvaluationDetailResponse> evaluationDetails;
}
