package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateCategoryRequest;
import com.annotation.platform.dto.request.feasibility.UpdateCategoryRequest;
import com.annotation.platform.dto.response.feasibility.CategoryAssessmentResponse;
import com.annotation.platform.service.CategoryAssessmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/assessments/{assessmentId}/categories")
@RequiredArgsConstructor
public class CategoryAssessmentController {

    private final CategoryAssessmentService categoryAssessmentService;

    @PostMapping
    public Result<CategoryAssessmentResponse> addCategory(
            @PathVariable Long assessmentId,
            @Valid @RequestBody CreateCategoryRequest request) {

        log.info("添加类别: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());
        CategoryAssessmentResponse response = categoryAssessmentService.addCategory(assessmentId, request);
        return Result.success(response);
    }

    @PostMapping("/batch")
    public Result<List<CategoryAssessmentResponse>> batchAddCategories(
            @PathVariable Long assessmentId,
            @Valid @RequestBody List<CreateCategoryRequest> requests) {

        log.info("批量添加类别: assessmentId={}, count={}", assessmentId, requests.size());
        List<CategoryAssessmentResponse> responses = categoryAssessmentService.batchAddCategories(assessmentId, requests);
        return Result.success(responses);
    }

    @GetMapping
    public Result<List<CategoryAssessmentResponse>> listCategories(@PathVariable Long assessmentId) {
        log.info("查询评估下的所有类别: assessmentId={}", assessmentId);
        List<CategoryAssessmentResponse> responses = categoryAssessmentService.listByAssessment(assessmentId);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<CategoryAssessmentResponse> getCategory(@PathVariable Long id) {
        log.info("查询类别详情: id={}", id);
        CategoryAssessmentResponse response = categoryAssessmentService.getCategory(id);
        return Result.success(response);
    }

    @PutMapping("/{id}")
    public Result<CategoryAssessmentResponse> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCategoryRequest request) {

        log.info("更新类别: id={}", id);
        CategoryAssessmentResponse response = categoryAssessmentService.updateCategory(id, request);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        log.info("删除类别: id={}", id);
        categoryAssessmentService.deleteCategory(id);
        return Result.success();
    }
}
