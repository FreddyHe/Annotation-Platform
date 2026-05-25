package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.EdgeDeployment;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.repository.EdgeDeploymentRepository;
import com.annotation.platform.service.EdgeSimulatorService;
import com.annotation.platform.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ProjectAccessService projectAccessService;
    private final EdgeDeploymentRepository edgeDeploymentRepository;

    @PostMapping("/deploy")
    public Result<Map<String, Object>> deploy(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        Long projectId = asLong(request.get("projectId"));
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        EdgeDeployment deployment = edgeSimulatorService.deploy(
                projectId,
                asLong(request.get("modelRecordId")),
                request.get("edgeNodeName") != null ? request.get("edgeNodeName").toString() : null
        );
        return Result.success(edgeSimulatorService.toDeploymentResponse(deployment));
    }

    @PostMapping("/rollback")
    public Result<Map<String, Object>> rollback(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        Long projectId = asLong(request.get("projectId"));
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        EdgeDeployment deployment = edgeSimulatorService.rollback(
                projectId,
                asLong(request.get("deploymentId"))
        );
        return Result.success(edgeSimulatorService.toDeploymentResponse(deployment));
    }

    @PostMapping("/inference")
    public Result<Map<String, Object>> inference(
            @RequestParam(value = "deploymentId", required = false) Long deploymentId,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam(value = "archive", required = false) MultipartFile archive,
            @RequestParam(value = "mode", required = false, defaultValue = "COLLECT_AND_UPLOAD") String mode,
            HttpServletRequest httpRequest
    ) {
        try {
            if (deploymentId == null) {
                return Result.error("400", "缺少 deploymentId，请先部署模型后再执行模拟视频流推理");
            }
            requireDeploymentAccess(deploymentId, httpRequest);
            return Result.success(edgeSimulatorService.runInference(deploymentId, files, archive, mode));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
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
            @RequestParam(value = "mode", required = false, defaultValue = "COLLECT_AND_UPLOAD") String mode,
            HttpServletRequest httpRequest
    ) {
        try {
            if (deploymentId == null) {
                return Result.error("400", "缺少 deploymentId，请先部署模型后再执行模拟视频流推理");
            }
            requireDeploymentAccess(deploymentId, httpRequest);
            return Result.success(edgeSimulatorService.startInferenceJob(deploymentId, files, archive, mode));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (BusinessException | IllegalArgumentException e) {
            return Result.error("400", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit edge inference job", e);
            return Result.error("500", "Failed to submit edge inference job: " + e.getMessage());
        }
    }

    @GetMapping("/inference-jobs/{jobId}")
    public Result<Map<String, Object>> inferenceJob(@PathVariable String jobId, HttpServletRequest httpRequest) {
        Map<String, Object> job = edgeSimulatorService.getInferenceJob(jobId);
        projectAccessService.requireProjectAccess(asLong(job.get("projectId")), httpRequest);
        return Result.success(job);
    }

    @GetMapping("/deployments")
    public Result<List<Map<String, Object>>> deployments(@RequestParam Long projectId, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        return Result.success(edgeSimulatorService.listDeployments(projectId).stream()
                .map(edgeSimulatorService::toDeploymentResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/inference-history")
    public Result<List<Map<String, Object>>> inferenceHistory(@RequestParam Long deploymentId, HttpServletRequest httpRequest) {
        requireDeploymentAccess(deploymentId, httpRequest);
        return Result.success(edgeSimulatorService.inferenceHistory(deploymentId).stream()
                .map(edgeSimulatorService::toPointResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/pool-stats")
    public Result<Map<String, Object>> poolStats(@RequestParam Long projectId, @RequestParam Long roundId, HttpServletRequest httpRequest) {
        projectAccessService.requireProjectAccess(projectId, httpRequest);
        return Result.success(edgeSimulatorService.poolStats(projectId, roundId));
    }

    private EdgeDeployment requireDeploymentAccess(Long deploymentId, HttpServletRequest httpRequest) {
        EdgeDeployment deployment = edgeDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new com.annotation.platform.exception.ResourceNotFoundException("EdgeDeployment", "id", deploymentId));
        projectAccessService.requireProjectAccess(deployment.getProjectId(), httpRequest);
        return deployment;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        return Long.parseLong(value.toString());
    }
}
