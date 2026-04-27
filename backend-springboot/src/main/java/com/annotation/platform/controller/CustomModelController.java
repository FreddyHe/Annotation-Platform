package com.annotation.platform.controller;

import com.annotation.platform.dto.CreateTrainingTaskRequest;
import com.annotation.platform.dto.CustomModelResponse;
import com.annotation.platform.entity.CustomModel;
import com.annotation.platform.service.CustomModelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/custom-models")
@RequiredArgsConstructor
public class CustomModelController {

    private final CustomModelService customModelService;

    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> createTrainingTask(
            @Valid @RequestBody CreateTrainingTaskRequest request,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        CustomModel model = customModelService.createTrainingTask(userId, request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", model.getId(), "status", model.getStatus()),
                "message", "训练任务已创建"
        ));
    }

    @PostMapping("/inspect-dataset")
    public ResponseEntity<Map<String, Object>> inspectDataset(
            @RequestBody CreateTrainingTaskRequest request) {
        Map<String, Object> inspection = customModelService.inspectDataset(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", inspection.getOrDefault("data", inspection),
                "message", inspection.getOrDefault("message", "数据集预检完成")
        ));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getTrainingStatus(@PathVariable Long id) {
        customModelService.syncTrainingStatus(id);
        CustomModelResponse detail = customModelService.getModelDetail(id);
        return ResponseEntity.ok(Map.of("success", true, "data", detail));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getModel(@PathVariable Long id) {
        CustomModelResponse detail = customModelService.getModelDetail(id);
        return ResponseEntity.ok(Map.of("success", true, "data", detail));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listModels(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        List<CustomModelResponse> models = customModelService.getUserModels(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", models));
    }

    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailableModels(HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        List<CustomModelResponse> models = customModelService.getAvailableModels(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", models));
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<Map<String, Object>> getTrainingLogs(@PathVariable Long id) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", customModelService.getTrainingLog(id));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryTrainingTask(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        CustomModel model = customModelService.retryTrainingTask(userId, id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", model.getId(), "status", model.getStatus()),
                "message", "训练任务已重新提交"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        customModelService.deleteModel(userId, id);
        return ResponseEntity.ok(Map.of("success", true, "message", "模型已删除"));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> trainingCallback(
            @RequestBody Map<String, Object> callbackData) {
        customModelService.handleCallback(callbackData);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId instanceof Long ? (Long) userId : 1L;
    }
}
