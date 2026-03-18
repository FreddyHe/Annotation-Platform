package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateVlmQualityScoreRequest;
import com.annotation.platform.dto.response.feasibility.VlmQualityScoreResponse;
import com.annotation.platform.service.VlmQualityScoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/ovd-results/{ovdResultId}/quality-scores")
@RequiredArgsConstructor
public class VlmQualityScoreController {

    private final VlmQualityScoreService vlmQualityScoreService;

    @PostMapping
    public Result<VlmQualityScoreResponse> create(
            @PathVariable Long ovdResultId,
            @Valid @RequestBody CreateVlmQualityScoreRequest request) {
        log.info("创建质量评分: ovdResultId={}", ovdResultId);
        VlmQualityScoreResponse response = vlmQualityScoreService.create(ovdResultId, request);
        return Result.success(response);
    }

    @GetMapping
    public Result<List<VlmQualityScoreResponse>> list(@PathVariable Long ovdResultId) {
        log.info("查询 OVD 结果下所有质量评分: ovdResultId={}", ovdResultId);
        List<VlmQualityScoreResponse> responses = vlmQualityScoreService.listByOvdResult(ovdResultId);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<VlmQualityScoreResponse> get(@PathVariable Long id) {
        log.info("查询质量评分详情: id={}", id);
        VlmQualityScoreResponse response = vlmQualityScoreService.getById(id);
        return Result.success(response);
    }
}
