package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.ProjectConfig;
import com.annotation.platform.service.ProjectConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/config")
public class ProjectConfigController {

    private final ProjectConfigService projectConfigService;

    @GetMapping
    public Result<ProjectConfig> getConfig(@PathVariable Long projectId) {
        return Result.success(projectConfigService.getOrCreate(projectId));
    }

    @PutMapping
    public Result<ProjectConfig> updateConfig(@PathVariable Long projectId, @RequestBody Map<String, Object> updates) {
        return Result.success(projectConfigService.update(projectId, updates));
    }
}
