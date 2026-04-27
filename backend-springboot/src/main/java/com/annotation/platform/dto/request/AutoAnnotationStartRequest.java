package com.annotation.platform.dto.request;

import com.annotation.platform.entity.AutoAnnotationJob;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoAnnotationStartRequest {
    @Builder.Default
    private String processRange = "unprocessed";

    @Builder.Default
    private AutoAnnotationJob.AnnotationMode mode = AutoAnnotationJob.AnnotationMode.DINO_VLM;

    @Builder.Default
    private Double scoreThreshold = 0.7;

    private Double boxThreshold;

    private Double textThreshold;
}
