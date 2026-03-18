package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateImplementationPlanRequest {

    private Integer phaseOrder;

    @Size(max = 100, message = "阶段名长度不能超过100个字符")
    private String phaseName;

    private String description;

    private Integer estimatedDays;

    private String tasks;

    private String deliverables;

    @Size(max = 500, message = "依赖长度不能超过500个字符")
    private String dependencies;
}
