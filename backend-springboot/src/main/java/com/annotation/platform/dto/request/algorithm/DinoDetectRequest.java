package com.annotation.platform.dto.request.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DinoDetectRequest {
    private Long projectId;
    private List<String> imagePaths;
    private List<String> labels;
    private String apiKey;
    private String endpoint;
    private String taskId;
}
