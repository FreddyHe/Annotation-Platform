package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateResourceEstimationRequest;
import com.annotation.platform.dto.response.feasibility.ResourceEstimationResponse;
import com.annotation.platform.service.ResourceEstimationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/assessments/{assessmentId}/resource-estimations")
@RequiredArgsConstructor
public class ResourceEstimationController {

    private final ResourceEstimationService resourceEstimationService;

    @PostMapping
    public Result<ResourceEstimationResponse> create(
            @PathVariable Long assessmentId,
            @Valid @RequestBody CreateResourceEstimationRequest request) {
        log.info("创建资源估算: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());
        ResourceEstimationResponse response = resourceEstimationService.create(assessmentId, request);
        return Result.success(response);
    }

    @PostMapping("/batch")
    public Result<List<ResourceEstimationResponse>> batchCreate(
            @PathVariable Long assessmentId,
            @Valid @RequestBody List<CreateResourceEstimationRequest> requests) {
        log.info("批量创建资源估算: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);
        List<ResourceEstimationResponse> responses = resourceEstimationService.batchCreate(assessmentId, requests);
        return Result.success(responses);
    }

    @GetMapping
    public Result<List<ResourceEstimationResponse>> list(
            @PathVariable Long assessmentId,
            @RequestParam(required = false) String categoryName) {
        log.info("查询资源估算: assessmentId={}, categoryName={}", assessmentId, categoryName);
        List<ResourceEstimationResponse> responses = resourceEstimationService.list(assessmentId, categoryName);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<ResourceEstimationResponse> get(@PathVariable Long id) {
        log.info("查询资源估算详情: id={}", id);
        ResourceEstimationResponse response = resourceEstimationService.getById(id);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除资源估算: id={}", id);
        resourceEstimationService.delete(id);
        return Result.success();
    }
}
