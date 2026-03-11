package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.service.algorithm.AutoAnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auto-annotation")
@RequiredArgsConstructor
public class AutoAnnotationController {

    private final AutoAnnotationService autoAnnotationService;

    /**
     * 启动自动标注流程
     */
    @PostMapping("/start/{projectId}")
    public Result<String> startAutoAnnotation(@PathVariable Long projectId, HttpServletRequest httpRequest) {
        log.info("Received request to start auto annotation: projectId={}", projectId);
        
        try {
            Long userId = (Long) httpRequest.getAttribute("userId");
            
            // 异步启动自动标注
            autoAnnotationService.startAutoAnnotation(projectId, userId);
            
            return Result.success("Auto annotation started successfully");
            
        } catch (Exception e) {
            log.error("Failed to start auto annotation: {}", e.getMessage(), e);
            return Result.error("Failed to start auto annotation: " + e.getMessage());
        }
    }

    /**
     * 查询自动标注任务状态
     */
    @GetMapping("/status/{taskId}")
    public Result<Object> getTaskStatus(@PathVariable String taskId) {
        log.info("Querying task status: taskId={}", taskId);
        
        try {
            // 这里应该调用 AlgorithmService 的 getTaskStatus 方法
            // 由于当前实现中 AlgorithmService 返回的是 Object，这里暂时返回成功
            return Result.success("Task status query not implemented yet");
            
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
