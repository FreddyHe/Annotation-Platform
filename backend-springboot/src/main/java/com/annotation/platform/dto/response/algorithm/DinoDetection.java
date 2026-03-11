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
public class DinoDetection {
    private String label;
    private List<Double> bbox;
    private Double score;
}
