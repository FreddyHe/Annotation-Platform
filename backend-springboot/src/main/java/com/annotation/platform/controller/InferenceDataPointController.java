package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.service.EdgeSimulatorService;
import com.annotation.platform.service.ProjectAccessService;
import com.annotation.platform.service.VlmJudgeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class InferenceDataPointController {

    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final EdgeSimulatorService edgeSimulatorService;
    private final VlmJudgeService vlmJudgeService;
    private final ProjectAccessService projectAccessService;

    @GetMapping("/inference-data-points/{id}")
    public Result<Map<String, Object>> getPoint(@PathVariable Long id, HttpServletRequest httpRequest) {
        InferenceDataPoint point = inferenceDataPointRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inference data point not found"));
        projectAccessService.requireProjectAccess(point.getProjectId(), httpRequest);
        return Result.success(edgeSimulatorService.toPointResponse(point));
    }

    @GetMapping("/projects/{projectId}/data-points")
    public Result<List<Map<String, Object>>> listPoints(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long roundId,
            @RequestParam(required = false) InferenceDataPoint.PoolType poolType,
            HttpServletRequest httpRequest
    ) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        List<InferenceDataPoint> points;
        if (roundId != null && poolType != null) {
            points = inferenceDataPointRepository.findByRoundIdAndPoolType(roundId, poolType);
        } else if (roundId != null) {
            points = inferenceDataPointRepository.findByRoundIdOrderByCreatedAtDesc(roundId);
        } else {
            points = inferenceDataPointRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }
        return Result.success(points.stream().map(edgeSimulatorService::toPointResponse).collect(Collectors.toList()));
    }

    @PostMapping("/projects/{projectId}/data-points/judge")
    public Result<Map<String, Object>> judge(@PathVariable Long projectId, @RequestParam Long roundId, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        return Result.success(vlmJudgeService.judgeAndSplit(projectId, roundId));
    }

    @PostMapping("/inference-data-points/{id}/review")
    public Result<Map<String, Object>> review(@PathVariable Long id, @RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        InferenceDataPoint existing = inferenceDataPointRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inference data point not found"));
        projectAccessService.requireProjectAccess(existing.getProjectId(), httpRequest);
        boolean reviewed = Boolean.parseBoolean(String.valueOf(request.getOrDefault("reviewed", true)));
        InferenceDataPoint point = vlmJudgeService.manualReview(id, reviewed);
        return Result.success(edgeSimulatorService.toPointResponse(point));
    }
}
