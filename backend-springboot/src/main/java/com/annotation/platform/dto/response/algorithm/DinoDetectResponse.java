package com.annotation.platform.dto.response.algorithm;

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
public class DinoDetectResponse {
    private Boolean success;
    private String message;
    private String taskId;
    private String status;
    private List<Map<String, Object>> results;
}
