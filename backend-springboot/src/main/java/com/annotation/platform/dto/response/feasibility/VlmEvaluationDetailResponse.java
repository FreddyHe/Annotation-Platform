package com.annotation.platform.dto.response.feasibility;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmEvaluationDetailResponse {
    private Long id;
    private Integer bboxIdx;
    private Boolean isCorrect;
    private String croppedImagePath;
    private String question;
    private String vlmAnswer;
    private String bboxJson;
    private String errorReason;
}
