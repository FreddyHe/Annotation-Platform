package com.annotation.platform.dto.response.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusResponse {

    private Long id;
    private String type;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Map<String, Object> parameters;
    private ProgressInfo progress;
    private String errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressInfo {
        private Integer totalImages;
        private Integer processedImages;
        private Integer percentage;
    }
}
