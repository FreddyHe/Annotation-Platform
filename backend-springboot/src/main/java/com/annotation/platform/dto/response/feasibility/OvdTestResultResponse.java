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
public class OvdTestResultResponse {

    private Long id;
    private Long assessmentId;
    private String categoryName;
    private String imagePath;
    private String promptUsed;
    private Integer detectedCount;
    private Double averageConfidence;
    private String bboxJson;
    private String annotatedImagePath;
    private LocalDateTime testTime;
}
