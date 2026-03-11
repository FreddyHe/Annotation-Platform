package com.annotation.platform.dto.request.project;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateProjectRequest {

    @Size(max = 100, message = "项目名称长度不能超过100")
    private String name;

    private List<String> labels;

    private java.util.Map<String, String> labelDefinitions;
}
