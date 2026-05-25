package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.ProjectConfig;
import com.annotation.platform.service.ProjectAccessService;
import com.annotation.platform.service.ProjectConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/config")
public class ProjectConfigController {

    private final ProjectConfigService projectConfigService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public Result<ProjectConfig> getConfig(@PathVariable Long projectId, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        return Result.success(projectConfigService.getOrCreate(projectId));
    }

    @PutMapping
    public Result<ProjectConfig> updateConfig(@PathVariable Long projectId, @RequestBody Map<String, Object> updates, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        return Result.success(projectConfigService.update(projectId, updates));
    }
}
