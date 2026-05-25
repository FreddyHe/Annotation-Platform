package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.AutoAnnotationStartRequest;
import com.annotation.platform.entity.AutoAnnotationJob;
import com.annotation.platform.entity.Project;
import com.annotation.platform.service.ProjectAccessService;
import com.annotation.platform.service.algorithm.AutoAnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auto-annotation")
@RequiredArgsConstructor
public class AutoAnnotationController {

    private final AutoAnnotationService autoAnnotationService;
    private final ProjectAccessService projectAccessService;

    /**
     * 启动自动标注流程
     */
    @PostMapping("/start/{projectId}")
    public Result<Map<String, Object>> startAutoAnnotation(
            @PathVariable Long projectId, 
            @RequestBody(required = false) AutoAnnotationStartRequest request,
            HttpServletRequest httpRequest) {
        
        AutoAnnotationStartRequest startRequest = request != null ? request : AutoAnnotationStartRequest.builder().build();
        if (startRequest.getProcessRange() == null || startRequest.getProcessRange().isBlank()) {
            startRequest.setProcessRange("unprocessed");
        }
        if (startRequest.getMode() == null) {
            startRequest.setMode(AutoAnnotationJob.AnnotationMode.DINO_VLM);
        }
        if (startRequest.getScoreThreshold() == null) {
            startRequest.setScoreThreshold(0.7);
        }

        log.info("Received request to start auto annotation: projectId={}, processRange={}, mode={}",
                projectId, startRequest.getProcessRange(), startRequest.getMode());

        projectAccessService.requireProjectAccess(projectId, httpRequest);
        Long userId = projectAccessService.currentUserId(httpRequest);

        try {
            AutoAnnotationJob job = autoAnnotationService.createJob(projectId, userId, startRequest);
            autoAnnotationService.startAutoAnnotationJob(job.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("taskId", "job-" + job.getId());
            response.put("jobId", job.getId());
            response.put("message", "Auto annotation started successfully");
            
            return Result.success(response);
            
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to start auto annotation: {}", e.getMessage(), e);
            return Result.error("Failed to start auto annotation: " + e.getMessage());
        }
    }

    /**
     * 查询自动标注任务状态
     */
    @GetMapping("/status/{taskId}")
    public Result<Map<String, Object>> getTaskStatus(@PathVariable String taskId, HttpServletRequest httpRequest) {
        log.info("Querying task status: taskId={}", taskId);
        
        try {
            if (taskId.startsWith("job-")) {
                Long jobId = Long.parseLong(taskId.replace("job-", ""));
                projectAccessService.requireAutoAnnotationJobInCurrentOrg(jobId, httpRequest);
                return Result.success(autoAnnotationService.getJobStatus(jobId));
            }

            // 从 taskId 中提取 projectId (格式: project-{projectId})
            Long projectId = Long.parseLong(taskId.replace("project-", ""));
            Project project = projectAccessService.requireProjectInCurrentOrg(projectId, httpRequest);
            
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
            
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get task status: {}", e.getMessage(), e);
            return Result.error("Failed to get task status: " + e.getMessage());
        }
    }

    /**
     * 获取自动标注任务结果
     */
    @GetMapping("/results/{taskId}")
    public Result<Object> getTaskResults(@PathVariable String taskId, HttpServletRequest httpRequest) {
        log.info("Querying task results: taskId={}", taskId);
        
        if (taskId.startsWith("job-")) {
            Long jobId = Long.parseLong(taskId.replace("job-", ""));
            projectAccessService.requireAutoAnnotationJobInCurrentOrg(jobId, httpRequest);
        } else {
            Long projectId = Long.parseLong(taskId.replace("project-", ""));
            projectAccessService.requireProjectAccess(projectId, httpRequest);
        }
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "自动标注结果查询接口尚未实现，请使用项目图片列表或导出功能查看结果");
    }

    @GetMapping("/jobs/{jobId}")
    public Result<Map<String, Object>> getJob(@PathVariable Long jobId, HttpServletRequest httpRequest) {
        projectAccessService.requireAutoAnnotationJobInCurrentOrg(jobId, httpRequest);
        return Result.success(autoAnnotationService.getJobStatus(jobId));
    }

    @GetMapping("/projects/{projectId}/jobs/latest")
    public Result<Map<String, Object>> getLatestProjectJob(@PathVariable Long projectId, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        Map<String, Object> status = autoAnnotationService.getLatestJobStatus(projectId);
        return Result.success(status);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public Result<Map<String, Object>> cancelJob(@PathVariable Long jobId, HttpServletRequest httpRequest) {
        projectAccessService.requireAutoAnnotationJobInCurrentOrg(jobId, httpRequest);
        return Result.success(autoAnnotationService.cancelJob(jobId));
    }
}
