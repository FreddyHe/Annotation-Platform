package com.annotation.platform.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class CustomModelResponse {
    private Long id;
    private String modelName;
    private Long projectId;
    private String targetClassName;
    private String datasetSource;
    private String datasetUri;
    private String status;
    private String statusMessage;
    private Integer epochs;
    private Integer batchSize;
    private Integer imageSize;
    private Double learningRate;
    private Boolean usePretrained;
    private Double progress;
    private Double mapScore;
    private Double precisionScore;
    private Double recallScore;
    private String modelPath;
    private String datasetFormat;
    private String trainingLog;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private List<ModelClassInfo> classes;

    @Data @Builder
    public static class ModelClassInfo {
        private Integer classId;
        private String className;
        private String cnName;
    }
}
