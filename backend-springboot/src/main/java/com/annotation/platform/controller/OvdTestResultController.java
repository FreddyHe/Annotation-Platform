package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateOvdTestResultRequest;
import com.annotation.platform.dto.response.feasibility.OvdTestResultResponse;
import com.annotation.platform.service.OvdTestResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/assessments/{assessmentId}/ovd-results")
@RequiredArgsConstructor
public class OvdTestResultController {

    private final OvdTestResultService ovdTestResultService;

    @PostMapping
    public Result<OvdTestResultResponse> create(
            @PathVariable Long assessmentId,
            @Valid @RequestBody CreateOvdTestResultRequest request) {
        log.info("创建 OVD 测试结果: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());
        OvdTestResultResponse response = ovdTestResultService.create(assessmentId, request);
        return Result.success(response);
    }

    @GetMapping
    public Result<List<OvdTestResultResponse>> list(@PathVariable Long assessmentId) {
        log.info("查询评估下所有 OVD 测试结果: assessmentId={}", assessmentId);
        List<OvdTestResultResponse> responses = ovdTestResultService.listByAssessment(assessmentId);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<OvdTestResultResponse> get(@PathVariable Long id) {
        log.info("查询 OVD 测试结果详情: id={}", id);
        OvdTestResultResponse response = ovdTestResultService.getById(id);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除 OVD 测试结果: id={}", id);
        ovdTestResultService.delete(id);
        return Result.success();
    }
}
