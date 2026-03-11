package com.annotation.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingStartRequest {
    private Long projectId;
    private String labelStudioProjectId;
    private String lsToken;
    private Integer epochs;
    private Integer batchSize;
    private Integer imageSize;
    private String modelType;
    private String device;
}
