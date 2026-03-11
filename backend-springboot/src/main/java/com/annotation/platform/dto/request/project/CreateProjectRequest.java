package com.annotation.platform.dto.request.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(min = 3, max = 100, message = "项目名称长度必须在3-100个字符之间")
    private String name;

    @NotNull(message = "标签不能为空")
    private List<String> labels;
}
