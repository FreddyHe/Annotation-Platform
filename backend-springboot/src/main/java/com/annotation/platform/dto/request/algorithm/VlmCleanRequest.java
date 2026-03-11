package com.annotation.platform.dto.request.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmCleanRequest {
    private Long projectId;
    private List<Map<String, Object>> detections;
    private Map<String, String> labelDefinitions;
    private String apiKey;
    private String endpoint;
    private String taskId;
    private List<String> imagePaths;
}
