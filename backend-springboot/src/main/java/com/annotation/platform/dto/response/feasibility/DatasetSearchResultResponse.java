package com.annotation.platform.dto.response.feasibility;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetSearchResultResponse {

    private Long id;
    private Long assessmentId;
    private String categoryName;
    private String source;
    private String datasetName;
    private String datasetUrl;
    private Integer sampleCount;
    private List<String> categories;
    private String annotationFormat;
    private String license;
    private Double relevanceScore;
    private LocalDateTime createdAt;
}
