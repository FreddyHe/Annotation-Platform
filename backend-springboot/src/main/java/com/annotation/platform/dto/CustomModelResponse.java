package com.annotation.platform.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class CustomModelResponse {
    private Long id;
    private String modelName;
    private String status;
    private String statusMessage;
    private Integer epochs;
    private Double mapScore;
    private String modelPath;
    private String datasetFormat;
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
