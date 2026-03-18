package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateDatasetSearchRequest {

    @Size(max = 200, message = "检测类别长度不能超过200个字符")
    private String categoryName;

    @NotBlank(message = "数据源不能为空")
    @Size(max = 50, message = "数据源长度不能超过50个字符")
    private String source;

    @NotBlank(message = "数据集名称不能为空")
    @Size(max = 200, message = "数据集名称长度不能超过200个字符")
    private String datasetName;

    @Size(max = 500, message = "数据集链接长度不能超过500个字符")
    private String datasetUrl;

    private Integer sampleCount;

    private List<String> categories;

    @Size(max = 50, message = "标注格式长度不能超过50个字符")
    private String annotationFormat;

    @Size(max = 100, message = "许可证长度不能超过100个字符")
    private String license;

    @NotNull(message = "相关性评分不能为空")
    private Double relevanceScore;
}
