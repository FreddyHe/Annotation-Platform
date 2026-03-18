package com.annotation.platform.dto.request.feasibility;

import com.annotation.platform.entity.AssessmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateStatusRequest {

    @NotNull(message = "状态不能为空")
    private AssessmentStatus status;
}