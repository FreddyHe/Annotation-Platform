package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateAssessmentRequest;
import com.annotation.platform.dto.request.feasibility.UpdateStatusRequest;
import com.annotation.platform.dto.request.feasibility.UserJudgmentRequest;
import com.annotation.platform.dto.response.feasibility.FeasibilityAssessmentResponse;
import com.annotation.platform.service.FeasibilityAssessmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility")
@RequiredArgsConstructor
public class FeasibilityAssessmentController {

    private final FeasibilityAssessmentService feasibilityAssessmentService;

    @PostMapping("/assessments")
    public Result<FeasibilityAssessmentResponse> createAssessment(
            @Valid @RequestBody CreateAssessmentRequest request,
            HttpServletRequest httpRequest) {

        Long userId = (Long) httpRequest.getAttribute("userId");
        Long organizationId = (Long) httpRequest.getAttribute("organizationId");

        log.info("创建可行性评估: name={}, userId={}, orgId={}", request.getAssessmentName(), userId, organizationId);

        FeasibilityAssessmentResponse response = feasibilityAssessmentService.createAssessment(request, userId, organizationId);
        return Result.success(response);
    }

    @GetMapping("/assessments")
    public Result<List<FeasibilityAssessmentResponse>> listAssessments(HttpServletRequest request) {
        Long organizationId = (Long) request.getAttribute("organizationId");
        log.info("查询可行性评估列表: orgId={}", organizationId);

        List<FeasibilityAssessmentResponse> assessments = feasibilityAssessmentService.listAssessments(organizationId);
        return Result.success(assessments);
    }

    @GetMapping("/assessments/{id}")
    public Result<FeasibilityAssessmentResponse> getAssessment(@PathVariable Long id) {
        log.info("查询可行性评估详情: id={}", id);
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.getAssessment(id);
        return Result.success(response);
    }

    @PutMapping("/assessments/{id}/status")
    public Result<FeasibilityAssessmentResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStatusRequest request) {

        log.info("更新可行性评估状态: id={}, status={}", id, request.getStatus());
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.updateStatus(id, request.getStatus());
        return Result.success(response);
    }

    @DeleteMapping("/assessments/{id}")
    public Result<Void> deleteAssessment(@PathVariable Long id) {
        log.info("删除可行性评估: id={}", id);
        feasibilityAssessmentService.deleteAssessment(id);
        return Result.success();
    }

    @PostMapping("/assessments/{id}/parse")
    public Result<FeasibilityAssessmentResponse> parseRequirement(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("需求解析: assessmentId={}, userId={}", id, userId);
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.parseAssessment(id, userId);
        return Result.success(response);
    }

    @PostMapping("/assessments/{id}/run-ovd-test")
    public Result<FeasibilityAssessmentResponse> runOvdTest(
            @PathVariable Long id,
            @Valid @RequestBody com.annotation.platform.dto.request.feasibility.RunOvdTestRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("运行OVD测试: assessmentId={}, userId={}, imagePaths={}", id, userId, request.getImagePaths().size());
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.runOvdTest(id, request.getImagePaths(), userId);
        return Result.success(response);
    }

    @PostMapping("/assessments/{id}/evaluate")
    public Result<java.util.Map<String, Object>> evaluateAssessment(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("VLM评估: assessmentId={}, userId={}", id, userId);
        java.util.Map<String, Object> result = feasibilityAssessmentService.evaluateAssessment(id, userId);
        return Result.success(result);
    }

    @PostMapping("/assessments/{id}/search-datasets")
    public Result<FeasibilityAssessmentResponse> searchDatasets(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("数据集检索: assessmentId={}, userId={}", id, userId);
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.searchDatasets(id, userId);
        return Result.success(response);
    }

    @PostMapping("/assessments/{id}/user-judgment")
    public Result<FeasibilityAssessmentResponse> submitUserJudgment(
            @PathVariable Long id,
            @Valid @RequestBody UserJudgmentRequest request,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("提交用户判断: assessmentId={}, userId={}, datasetMatchLevel={}", id, userId, request.getDatasetMatchLevel());
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.submitUserJudgment(id, request);
        return Result.success(response);
    }

    @PostMapping("/assessments/{id}/estimate")
    public Result<FeasibilityAssessmentResponse> estimateResources(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("资源估算: assessmentId={}, userId={}", id, userId);
        FeasibilityAssessmentResponse response = feasibilityAssessmentService.estimateResources(id, userId);
        return Result.success(response);
    }

    @PostMapping("/assessments/{id}/generate-plan")
    public Result<java.util.Map<String, Object>> generateImplementationPlan(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("生成实施计划: assessmentId={}, userId={}", id, userId);
        java.util.Map<String, Object> result = feasibilityAssessmentService.generateImplementationPlan(id, userId);
        return Result.success(result);
    }

    @GetMapping("/assessments/{id}/report")
    public Result<java.util.Map<String, Object>> generateReport(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("生成完整报告: assessmentId={}, userId={}", id, userId);
        java.util.Map<String, Object> report = feasibilityAssessmentService.generateReport(id, userId);
        return Result.success(report);
    }

    @PostMapping("/assessments/{id}/ai-report")
    public Result<java.util.Map<String, Object>> generateAIReport(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        log.info("生成AI可行性报告: assessmentId={}, userId={}", id, userId);
        java.util.Map<String, Object> report = feasibilityAssessmentService.generateAIReport(id, userId);
        return Result.success(report);
    }
}
