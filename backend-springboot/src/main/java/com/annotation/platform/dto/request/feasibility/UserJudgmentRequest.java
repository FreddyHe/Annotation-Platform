package com.annotation.platform.dto.request.feasibility;

import com.annotation.platform.entity.DatasetMatchLevel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserJudgmentRequest {

    @NotNull(message = "数据集匹配度不能为空")
    private DatasetMatchLevel datasetMatchLevel;

    private String userNotes;
}
