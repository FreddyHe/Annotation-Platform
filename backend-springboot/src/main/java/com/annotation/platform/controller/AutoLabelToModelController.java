package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.Project;
import com.annotation.platform.service.AutoLabelToModelService;
import com.annotation.platform.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/projects/{projectId}")
@RequiredArgsConstructor
public class AutoLabelToModelController {

    private final AutoLabelToModelService autoLabelToModelService;
    private final ProjectAccessService projectAccessService;

    @PostMapping("/requirements/parse")
    public Result<Map<String, Object>> parseRequirement(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Project project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
        Long userId = projectAccessService.currentUserId(request);
        return Result.success(autoLabelToModelService.parseRequirement(project, userId, safeBody(body)));
    }

    @GetMapping("/requirements/latest")
    public Result<Map<String, Object>> getLatestRequirement(@PathVariable Long projectId, HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.getLatestRequirement(projectId));
    }

    @PostMapping("/dataset-profile")
    public Result<Map<String, Object>> createDatasetProfile(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Project project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
        return Result.success(autoLabelToModelService.createDatasetProfile(project, safeBody(body)));
    }

    @GetMapping("/dataset-profile/latest")
    public Result<Map<String, Object>> getLatestDatasetProfile(@PathVariable Long projectId, HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.getLatestDatasetProfile(projectId));
    }

    @PostMapping("/model-route/preview")
    public Result<Map<String, Object>> previewRoute(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Project project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
        return Result.success(autoLabelToModelService.previewRoute(project, safeBody(body)));
    }

    @GetMapping("/model-route/latest")
    public Result<Map<String, Object>> getLatestRoute(@PathVariable Long projectId, HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.getLatestRoute(projectId));
    }

    @PostMapping("/auto-label/jobs")
    public Result<Map<String, Object>> createAutoLabelJob(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Project project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
        Long userId = projectAccessService.currentUserId(request);
        return Result.success(autoLabelToModelService.createAutoLabelJob(project, userId, safeBody(body)));
    }

    @GetMapping("/auto-label/jobs")
    public Result<Map<String, Object>> listAutoLabelJobs(@PathVariable Long projectId, HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.listJobs(projectId));
    }

    @GetMapping("/auto-label/jobs/{jobId}")
    public Result<Map<String, Object>> getAutoLabelJob(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.getJob(projectId, jobId));
    }

    @PostMapping("/auto-label/jobs/{jobId}/probe")
    public Result<Map<String, Object>> probeAutoLabelJob(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.probeJob(projectId, jobId));
    }

    @PostMapping("/auto-label/jobs/{jobId}/run")
    public Result<Map<String, Object>> runAutoLabelJob(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.runJob(projectId, jobId));
    }

    @PostMapping("/auto-label/jobs/{jobId}/sync-label-studio")
    public Result<Map<String, Object>> syncAutoLabelJobToLabelStudio(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        Long userId = projectAccessService.currentUserId(request);
        return Result.success(autoLabelToModelService.syncLabelStudio(projectId, jobId, userId));
    }

    @GetMapping("/auto-label/jobs/{jobId}/candidates")
    public Result<Map<String, Object>> listCandidates(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.listCandidates(projectId, jobId, page, size, limit, offset));
    }

    @GetMapping("/auto-label/jobs/{jobId}/fused-predictions")
    public Result<Map<String, Object>> listFusedPredictions(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.listFusedPredictions(projectId, jobId, page, size, limit, offset));
    }

    @GetMapping("/auto-label/jobs/{jobId}/annotated-video")
    public Result<Map<String, Object>> getAnnotatedVideo(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.getAnnotatedVideo(projectId, jobId));
    }

    @PostMapping("/auto-label/jobs/{jobId}/annotated-video")
    public Result<Map<String, Object>> renderAnnotatedVideo(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        return Result.success(autoLabelToModelService.renderAnnotatedVideo(projectId, jobId));
    }

    @PostMapping("/train-from-reviewed-labels")
    public Result<Map<String, Object>> trainFromReviewedLabels(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        projectAccessService.requireProjectAccess(projectId, request);
        Long userId = projectAccessService.currentUserId(request);
        return Result.success(autoLabelToModelService.trainFromReviewedLabels(projectId, safeBody(body), userId));
    }

    private Map<String, Object> safeBody(Map<String, Object> body) {
        return body == null ? Collections.emptyMap() : body;
    }
}
