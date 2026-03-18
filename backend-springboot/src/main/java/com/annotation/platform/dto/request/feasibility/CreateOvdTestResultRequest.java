package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateOvdTestResultRequest {

    @NotBlank(message = "类别名称不能为空")
    @Size(max = 100, message = "类别名称长度不能超过100个字符")
    private String categoryName;

    @NotBlank(message = "原图路径不能为空")
    @Size(max = 500, message = "原图路径长度不能超过500个字符")
    private String imagePath;

    private String promptUsed;

    @NotNull(message = "检测到数量不能为空")
    private Integer detectedCount;

    @NotNull(message = "平均置信度不能为空")
    private Double averageConfidence;

    private String bboxJson;

    @Size(max = 500, message = "标注后图片路径长度不能超过500个字符")
    private String annotatedImagePath;
}
