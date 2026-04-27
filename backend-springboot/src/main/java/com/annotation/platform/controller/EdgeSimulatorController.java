package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.EdgeDeployment;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.service.EdgeSimulatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/edge-simulator")
public class EdgeSimulatorController {

    private final EdgeSimulatorService edgeSimulatorService;

    @PostMapping("/deploy")
    public Result<Map<String, Object>> deploy(@RequestBody Map<String, Object> request) {
        EdgeDeployment deployment = edgeSimulatorService.deploy(
                asLong(request.get("projectId")),
                asLong(request.get("modelRecordId")),
                request.get("edgeNodeName") != null ? request.get("edgeNodeName").toString() : null
        );
        return Result.success(edgeSimulatorService.toDeploymentResponse(deployment));
    }

    @PostMapping("/rollback")
    public Result<Map<String, Object>> rollback(@RequestBody Map<String, Object> request) {
        EdgeDeployment deployment = edgeSimulatorService.rollback(
                asLong(request.get("projectId")),
                asLong(request.get("deploymentId"))
        );
        return Result.success(edgeSimulatorService.toDeploymentResponse(deployment));
    }

    @PostMapping("/inference")
    public Result<Map<String, Object>> inference(
            @RequestParam(value = "deploymentId", required = false) Long deploymentId,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "mode", required = false, defaultValue = "COLLECT_AND_UPLOAD") String mode
    ) {
        try {
            if (deploymentId == null) {
                return Result.error("400", "缺少 deploymentId，请先部署模型后再执行模拟视频流推理");
            }
            return Result.success(edgeSimulatorService.runInference(deploymentId, files, archive, mode));
        } catch (BusinessException | IllegalArgumentException e) {
            return Result.error("400", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to run edge inference", e);
            return Result.error("500", "Failed to run edge inference: " + e.getMessage());
        }
    }

    @PostMapping("/inference-async")
    public Result<Map<String, Object>> inferenceAsync(
            @RequestParam(value = "deploymentId", required = false) Long deploymentId,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "mode", required = false, defaultValue = "COLLECT_AND_UPLOAD") String mode
    ) {
        try {
            if (deploymentId == null) {
                return Result.error("400", "缺少 deploymentId，请先部署模型后再执行模拟视频流推理");
            }
            return Result.success(edgeSimulatorService.startInferenceJob(deploymentId, files, archive, mode));
        } catch (BusinessException | IllegalArgumentException e) {
            return Result.error("400", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit edge inference job", e);
            return Result.error("500", "Failed to submit edge inference job: " + e.getMessage());
        }
    }

    @GetMapping("/inference-jobs/{jobId}")
    public Result<Map<String, Object>> inferenceJob(@PathVariable String jobId) {
        return Result.success(edgeSimulatorService.getInferenceJob(jobId));
    }

    @GetMapping("/deployments")
    public Result<List<Map<String, Object>>> deployments(@RequestParam Long projectId) {
        return Result.success(edgeSimulatorService.listDeployments(projectId).stream()
                .map(edgeSimulatorService::toDeploymentResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/inference-history")
    public Result<List<Map<String, Object>>> inferenceHistory(@RequestParam Long deploymentId) {
        return Result.success(edgeSimulatorService.inferenceHistory(deploymentId).stream()
                .map(edgeSimulatorService::toPointResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/pool-stats")
    public Result<Map<String, Object>> poolStats(@RequestParam Long projectId, @RequestParam Long roundId) {
        return Result.success(edgeSimulatorService.poolStats(projectId, roundId));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        return Long.parseLong(value.toString());
    }
}
