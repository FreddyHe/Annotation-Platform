package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.service.AutoLabelToModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/models/registry")
@RequiredArgsConstructor
public class ModelRegistryController {

    private final AutoLabelToModelService autoLabelToModelService;

    @GetMapping
    public Result<Map<String, Object>> listModels(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String modelType) {
        return Result.success(autoLabelToModelService.listModelRegistry(status, modelType));
    }

    @PostMapping("/sync")
    public Result<Map<String, Object>> syncModels() {
        return Result.success(autoLabelToModelService.syncModelRegistry());
    }

    @GetMapping("/{modelId}")
    public Result<Map<String, Object>> getModel(@PathVariable String modelId) {
        return Result.success(autoLabelToModelService.getModel(modelId));
    }
}
