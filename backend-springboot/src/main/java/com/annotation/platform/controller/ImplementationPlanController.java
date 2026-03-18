package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateImplementationPlanRequest;
import com.annotation.platform.dto.request.feasibility.UpdateImplementationPlanRequest;
import com.annotation.platform.dto.response.feasibility.ImplementationPlanResponse;
import com.annotation.platform.service.ImplementationPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/assessments/{assessmentId}/implementation-plans")
@RequiredArgsConstructor
public class ImplementationPlanController {

    private final ImplementationPlanService implementationPlanService;

    @PostMapping
    public Result<ImplementationPlanResponse> create(
            @PathVariable Long assessmentId,
            @Valid @RequestBody CreateImplementationPlanRequest request) {
        log.info("创建实施计划阶段: assessmentId={}, phaseOrder={}, phaseName={}", assessmentId, request.getPhaseOrder(), request.getPhaseName());
        ImplementationPlanResponse response = implementationPlanService.create(assessmentId, request);
        return Result.success(response);
    }

    @PostMapping("/batch")
    public Result<List<ImplementationPlanResponse>> batchCreate(
            @PathVariable Long assessmentId,
            @Valid @RequestBody List<CreateImplementationPlanRequest> requests) {
        log.info("批量创建实施计划阶段: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);
        List<ImplementationPlanResponse> responses = implementationPlanService.batchCreate(assessmentId, requests);
        return Result.success(responses);
    }

    @GetMapping
    public Result<List<ImplementationPlanResponse>> list(@PathVariable Long assessmentId) {
        log.info("查询实施计划阶段列表: assessmentId={}", assessmentId);
        List<ImplementationPlanResponse> responses = implementationPlanService.list(assessmentId);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<ImplementationPlanResponse> get(@PathVariable Long id) {
        log.info("查询实施计划阶段详情: id={}", id);
        ImplementationPlanResponse response = implementationPlanService.getById(id);
        return Result.success(response);
    }

    @PutMapping("/{id}")
    public Result<ImplementationPlanResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateImplementationPlanRequest request) {
        log.info("更新实施计划阶段: id={}", id);
        ImplementationPlanResponse response = implementationPlanService.update(id, request);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除实施计划阶段: id={}", id);
        implementationPlanService.delete(id);
        return Result.success();
    }
}
