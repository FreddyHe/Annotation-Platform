package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.algorithm.RunDinoDetectionRequest;
import com.annotation.platform.dto.request.algorithm.RunVlmCleaningRequest;
import com.annotation.platform.dto.request.algorithm.RunYoloDetectionRequest;
import com.annotation.platform.dto.response.algorithm.TaskStatusResponse;
import com.annotation.platform.entity.AnnotationTask;
import com.annotation.platform.repository.AnnotationTaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/algorithm")
@RequiredArgsConstructor
public class AlgorithmController {

    private final AnnotationTaskRepository annotationTaskRepository;

    @GetMapping("/tasks")
    public Result<Map<String, Object>> getTasks(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("查询任务列表: projectId={}, taskType={}, status={}, page={}, size={}", 
                projectId, taskType, status, page, size);

        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("startedAt").descending());
        
        Page<AnnotationTask> taskPage;
        if (projectId != null) {
            if (taskType != null && !taskType.isEmpty() && status != null && !status.isEmpty()) {
                taskPage = annotationTaskRepository.findByProjectIdAndStatus(
                    projectId, 
                    AnnotationTask.TaskStatus.valueOf(status), 
                    pageable
                );
            } else if (taskType != null && !taskType.isEmpty()) {
                List<AnnotationTask> tasks = annotationTaskRepository.findByProjectIdOrderByStartedAtDesc(projectId);
                taskPage = new org.springframework.data.domain.PageImpl<>(
                    tasks.stream()
                        .filter(t -> t.getType().name().equals(taskType))
                        .collect(Collectors.toList()),
                    pageable,
                    tasks.stream().filter(t -> t.getType().name().equals(taskType)).count()
                );
            } else if (status != null && !status.isEmpty()) {
                List<AnnotationTask> tasks = annotationTaskRepository.findByProjectIdOrderByStartedAtDesc(projectId);
                taskPage = new org.springframework.data.domain.PageImpl<>(
                    tasks.stream()
                        .filter(t -> t.getStatus().name().equals(status))
                        .collect(Collectors.toList()),
                    pageable,
                    tasks.stream().filter(t -> t.getStatus().name().equals(status)).count()
                );
            } else {
                taskPage = annotationTaskRepository.findByProjectId(projectId, pageable);
            }
        } else {
            taskPage = annotationTaskRepository.findAll(pageable);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("tasks", taskPage.getContent().stream()
            .map(this::convertToTaskDTO)
            .collect(Collectors.toList())
        );
        response.put("total", taskPage.getTotalElements());
        response.put("page", taskPage.getNumber() + 1);
        response.put("size", taskPage.getSize());

        return Result.success(response);
    }

    private Map<String, Object> convertToTaskDTO(AnnotationTask task) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("taskId", task.getId());
        dto.put("taskType", task.getType().name());
        dto.put("status", task.getStatus().name());
        dto.put("progress", 0);
        dto.put("totalImages", 0);
        dto.put("processedImages", 0);
        dto.put("createdAt", task.getStartedAt());
        dto.put("completedAt", task.getCompletedAt());
        dto.put("parameters", task.getParameters());
        dto.put("errorMessage", task.getErrorMessage());
        return dto;
    }

    @PostMapping("/dino/detect")
    public Result<TaskStatusResponse> runDinoDetection(
            @Valid @RequestBody RunDinoDetectionRequest request,
            HttpServletRequest httpRequest) {

        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("运行 DINO 检测: userId={}, projectId={}, labels={}", userId, request.getProjectId(), request.getLabels());

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(1L)
                .type("DINO_DETECTION")
                .status("RUNNING")
                .parameters(Map.of(
                        "projectId", request.getProjectId(),
                        "labels", request.getLabels(),
                        "boxThreshold", request.getBoxThreshold(),
                        "textThreshold", request.getTextThreshold()
                ))
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(0)
                        .percentage(0)
                        .build())
                .build();

        return Result.success(response);
    }

    @PostMapping("/vlm/clean")
    public Result<TaskStatusResponse> runVlmCleaning(
            @Valid @RequestBody RunVlmCleaningRequest request,
            HttpServletRequest httpRequest) {

        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("运行 VLM 清洗: userId={}, projectId={}, model={}", userId, request.getProjectId(), request.getModel());

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(2L)
                .type("VLM_CLEANING")
                .status("RUNNING")
                .parameters(Map.of(
                        "projectId", request.getProjectId(),
                        "model", request.getModel(),
                        "maxTokens", request.getMaxTokens(),
                        "minDim", request.getMinDim()
                ))
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(0)
                        .percentage(0)
                        .build())
                .build();

        return Result.success(response);
    }

    @PostMapping("/yolo/detect")
    public Result<TaskStatusResponse> runYoloDetection(
            @Valid @RequestBody RunYoloDetectionRequest request,
            HttpServletRequest httpRequest) {

        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("运行 YOLO 检测: userId={}, projectId={}, labels={}", userId, request.getProjectId(), request.getLabels());

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(3L)
                .type("YOLO_DETECTION")
                .status("RUNNING")
                .parameters(Map.of(
                        "projectId", request.getProjectId(),
                        "labels", request.getLabels(),
                        "modelSize", request.getModelSize(),
                        "confThreshold", request.getConfThreshold(),
                        "iouThreshold", request.getIouThreshold()
                ))
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(0)
                        .percentage(0)
                        .build())
                .build();

        return Result.success(response);
    }

    @GetMapping("/yolo/status/{taskId}")
    public Result<TaskStatusResponse> getYoloTaskStatus(@PathVariable Long taskId) {
        log.info("查询 YOLO 任务状态: taskId={}", taskId);

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(taskId)
                .type("YOLO_DETECTION")
                .status("COMPLETED")
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(100)
                        .percentage(100)
                        .build())
                .build();

        return Result.success(response);
    }

    @GetMapping("/yolo/results/{taskId}")
    public Result<Map<String, Object>> getYoloTaskResults(@PathVariable Long taskId) {
        log.info("获取 YOLO 任务结果: taskId={}", taskId);

        Map<String, Object> results = new HashMap<>();
        results.put("taskId", taskId);
        results.put("totalDetections", 200);
        results.put("results", new Object[]{});

        return Result.success(results);
    }

    @PostMapping("/yolo/cancel/{taskId}")
    public Result<Void> cancelYoloTask(@PathVariable Long taskId) {
        log.info("取消 YOLO 任务: taskId={}", taskId);
        return Result.success();
    }

    @GetMapping("/dino/status/{taskId}")
    public Result<TaskStatusResponse> getDinoTaskStatus(@PathVariable Long taskId) {
        log.info("查询 DINO 任务状态: taskId={}", taskId);

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(taskId)
                .type("DINO_DETECTION")
                .status("COMPLETED")
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(100)
                        .percentage(100)
                        .build())
                .build();

        return Result.success(response);
    }

    @GetMapping("/dino/results/{taskId}")
    public Result<Map<String, Object>> getDinoTaskResults(@PathVariable Long taskId) {
        log.info("获取 DINO 任务结果: taskId={}", taskId);

        Map<String, Object> results = new HashMap<>();
        results.put("taskId", taskId);
        results.put("totalDetections", 150);
        results.put("results", new Object[]{});

        return Result.success(results);
    }

    @PostMapping("/dino/cancel/{taskId}")
    public Result<Void> cancelDinoTask(@PathVariable Long taskId) {
        log.info("取消 DINO 任务: taskId={}", taskId);
        return Result.success();
    }

    @GetMapping("/vlm/status/{taskId}")
    public Result<TaskStatusResponse> getVlmTaskStatus(@PathVariable Long taskId) {
        log.info("查询 VLM 任务状态: taskId={}", taskId);

        TaskStatusResponse response = TaskStatusResponse.builder()
                .id(taskId)
                .type("VLM_CLEANING")
                .status("COMPLETED")
                .progress(TaskStatusResponse.ProgressInfo.builder()
                        .totalImages(100)
                        .processedImages(100)
                        .percentage(100)
                        .build())
                .build();

        return Result.success(response);
    }

    @GetMapping("/vlm/results/{taskId}")
    public Result<Map<String, Object>> getVlmTaskResults(@PathVariable Long taskId) {
        log.info("获取 VLM 任务结果: taskId={}", taskId);

        Map<String, Object> results = new HashMap<>();
        results.put("taskId", taskId);
        results.put("totalCleaned", 80);
        results.put("results", new Object[]{});

        return Result.success(results);
    }

    @PostMapping("/vlm/cancel/{taskId}")
    public Result<Void> cancelVlmTask(@PathVariable Long taskId) {
        log.info("取消 VLM 任务: taskId={}", taskId);
        return Result.success();
    }
}
