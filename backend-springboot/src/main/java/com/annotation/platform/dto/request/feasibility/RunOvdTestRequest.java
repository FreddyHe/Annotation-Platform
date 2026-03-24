package com.annotation.platform.dto.request.feasibility;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class RunOvdTestRequest {
    @NotEmpty(message = "imagePaths cannot be empty")
    private List<String> imagePaths;
}
