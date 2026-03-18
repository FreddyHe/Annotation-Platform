package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateImplementationPlanRequest {

    @NotNull(message = "阶段顺序不能为空")
    private Integer phaseOrder;

    @NotBlank(message = "阶段名不能为空")
    @Size(max = 100, message = "阶段名长度不能超过100个字符")
    private String phaseName;

    private String description;

    private Integer estimatedDays;

    private String tasks;

    private String deliverables;

    @Size(max = 500, message = "依赖长度不能超过500个字符")
    private String dependencies;
}
