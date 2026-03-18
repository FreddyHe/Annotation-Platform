package com.annotation.platform.dto.response.feasibility;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImplementationPlanResponse {

    private Long id;
    private Long assessmentId;
    private Integer phaseOrder;
    private String phaseName;
    private String description;
    private Integer estimatedDays;
    private String tasks;
    private String deliverables;
    private String dependencies;
    private LocalDateTime createdAt;
}
