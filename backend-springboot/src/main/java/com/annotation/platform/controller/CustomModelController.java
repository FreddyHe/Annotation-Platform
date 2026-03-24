package com.annotation.platform.controller;

import com.annotation.platform.dto.CreateTrainingTaskRequest;
import com.annotation.platform.dto.CustomModelResponse;
import com.annotation.platform.entity.CustomModel;
import com.annotation.platform.service.CustomModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/custom-models")
@RequiredArgsConstructor
public class CustomModelController {

    private final CustomModelService customModelService;

    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> createTrainingTask(
            @Valid @RequestBody CreateTrainingTaskRequest request) {
        Long userId = 1L;
        CustomModel model = customModelService.createTrainingTask(userId, request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("id", model.getId(), "status", model.getStatus()),
                "message", "训练任务已创建"
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
    public ResponseEntity<Map<String, Object>> listModels() {
        Long userId = 1L;
        List<CustomModelResponse> models = customModelService.getUserModels(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", models));
    }

    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        Long userId = 1L;
        List<CustomModelResponse> models = customModelService.getAvailableModels(userId);
        return ResponseEntity.ok(Map.of("success", true, "data", models));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> trainingCallback(
            @RequestBody Map<String, Object> callbackData) {
        customModelService.handleCallback(callbackData);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
