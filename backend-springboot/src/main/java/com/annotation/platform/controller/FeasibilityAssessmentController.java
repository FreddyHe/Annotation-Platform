package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateAssessmentRequest;
import com.annotation.platform.dto.request.feasibility.UpdateStatusRequest;
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
}
