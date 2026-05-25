package com.annotation.platform.dto.request.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateProjectRequest {

    @Size(min = 3, max = 100, message = "项目名称长度必须在3-100个字符之间")
    private String name;

    @Size(max = 100, message = "标签数量不能超过100个")
    private List<@NotBlank(message = "标签名称不能为空") @Size(max = 80, message = "标签名称不能超过80个字符") String> labels;

    private java.util.Map<String, String> labelDefinitions;
}
