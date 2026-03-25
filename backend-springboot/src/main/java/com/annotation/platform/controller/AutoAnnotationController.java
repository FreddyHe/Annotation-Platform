package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.Project;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.algorithm.AutoAnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auto-annotation")
@RequiredArgsConstructor
public class AutoAnnotationController {

    private final AutoAnnotationService autoAnnotationService;
    private final ProjectRepository projectRepository;

    /**
     * 启动自动标注流程
     */
    @PostMapping("/start/{projectId}")
    public Result<Map<String, Object>> startAutoAnnotation(
            @PathVariable Long projectId, 
            @RequestBody(required = false) Map<String, String> params,
            HttpServletRequest httpRequest) {
        
        String processRange = params != null ? params.get("processRange") : "unprocessed";
        log.info("Received request to start auto annotation: projectId={}, processRange={}", projectId, processRange);
        
        try {
            Long userId = (Long) httpRequest.getAttribute("userId");
            
            // 异步启动自动标注
            autoAnnotationService.startAutoAnnotation(projectId, userId, processRange);
            
            Map<String, Object> response = new HashMap<>();
            response.put("taskId", "project-" + projectId);
            response.put("message", "Auto annotation started successfully");
            
            return Result.success(response);
            
        } catch (Exception e) {
            log.error("Failed to start auto annotation: {}", e.getMessage(), e);
            return Result.error("Failed to start auto annotation: " + e.getMessage());
        }
    }

    /**
     * 查询自动标注任务状态
     */
    @GetMapping("/status/{taskId}")
    public Result<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        log.info("Querying task status: taskId={}", taskId);
        
        try {
            // 从 taskId 中提取 projectId (格式: project-{projectId})
            Long projectId = Long.parseLong(taskId.replace("project-", ""));
            
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));
            
            Map<String, Object> statusData = new HashMap<>();
            statusData.put("taskId", taskId);
            statusData.put("projectId", projectId);
            
            // 根据项目状态映射到前端期望的状态
            String status;
            Project.ProjectStatus projectStatus = project.getStatus();
            if (projectStatus == Project.ProjectStatus.DRAFT) {
                status = "PENDING";
            } else if (projectStatus == Project.ProjectStatus.UPLOADING) {
                status = "UPLOADING";
            } else if (projectStatus == Project.ProjectStatus.DETECTING) {
                status = "DETECTING";
            } else if (projectStatus == Project.ProjectStatus.CLEANING) {
                status = "CLEANING";
            } else if (projectStatus == Project.ProjectStatus.SYNCING) {
                status = "SYNCING";
            } else if (projectStatus == Project.ProjectStatus.COMPLETED) {
                status = "COMPLETED";
            } else if (projectStatus == Project.ProjectStatus.FAILED) {
                status = "FAILED";
            } else {
                status = "PENDING";
            }
            
            statusData.put("status", status);
            
            return Result.success(statusData);
            
        } catch (Exception e) {
            log.error("Failed to get task status: {}", e.getMessage(), e);
            return Result.error("Failed to get task status: " + e.getMessage());
        }
    }

    /**
     * 获取自动标注任务结果
     */
    @GetMapping("/results/{taskId}")
    public Result<Object> getTaskResults(@PathVariable String taskId) {
        log.info("Querying task results: taskId={}", taskId);
        
        try {
            // 这里应该调用 AlgorithmService 的 getTaskResults 方法
            return Result.success("Task results query not implemented yet");
            
        } catch (Exception e) {
            log.error("Failed to get task results: {}", e.getMessage(), e);
            return Result.error("Failed to get task results: " + e.getMessage());
        }
    }
}
