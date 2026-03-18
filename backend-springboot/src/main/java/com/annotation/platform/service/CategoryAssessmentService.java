package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateCategoryRequest;
import com.annotation.platform.dto.request.feasibility.UpdateCategoryRequest;
import com.annotation.platform.dto.response.feasibility.CategoryAssessmentResponse;
import com.annotation.platform.entity.CategoryAssessment;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.FeasibilityBucket;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.CategoryAssessmentRepository;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryAssessmentService {

    private final CategoryAssessmentRepository categoryAssessmentRepository;
    private final FeasibilityAssessmentRepository assessmentRepository;

    @Transactional
    public CategoryAssessmentResponse addCategory(Long assessmentId, CreateCategoryRequest request) {
        log.info("添加类别到评估: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        CategoryAssessment categoryAssessment = CategoryAssessment.builder()
                .assessment(assessment)
                .categoryName(request.getCategoryName())
                .categoryNameEn(request.getCategoryNameEn())
                .categoryType(request.getCategoryType())
                .sceneDescription(request.getSceneDescription())
                .viewAngle(request.getViewAngle())
                .environmentConstraints(request.getEnvironmentConstraints())
                .performanceRequirements(request.getPerformanceRequirements())
                .feasibilityBucket(FeasibilityBucket.PENDING)
                .confidence(null)
                .reasoning(null)
                .build();

        CategoryAssessment savedCategory = categoryAssessmentRepository.save(categoryAssessment);
        log.info("类别添加成功: id={}, assessmentId={}", savedCategory.getId(), assessmentId);

        return convertToResponse(savedCategory);
    }

    @Transactional
    public List<CategoryAssessmentResponse> batchAddCategories(Long assessmentId, List<CreateCategoryRequest> requests) {
        log.info("批量添加类别到评估: assessmentId={}, count={}", assessmentId, requests.size());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<CategoryAssessment> categories = requests.stream()
                .map(request -> CategoryAssessment.builder()
                        .assessment(assessment)
                        .categoryName(request.getCategoryName())
                        .categoryNameEn(request.getCategoryNameEn())
                        .categoryType(request.getCategoryType())
                        .sceneDescription(request.getSceneDescription())
                        .viewAngle(request.getViewAngle())
                        .environmentConstraints(request.getEnvironmentConstraints())
                        .performanceRequirements(request.getPerformanceRequirements())
                        .feasibilityBucket(FeasibilityBucket.PENDING)
                        .confidence(null)
                        .reasoning(null)
                        .build())
                .collect(Collectors.toList());

        List<CategoryAssessment> savedCategories = categoryAssessmentRepository.saveAll(categories);
        log.info("批量添加类别成功: assessmentId={}, count={}", assessmentId, savedCategories.size());

        return savedCategories.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    public CategoryAssessmentResponse getCategory(Long categoryId) {
        log.info("查询类别详情: id={}", categoryId);
        CategoryAssessment category = categoryAssessmentRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("CategoryAssessment", "id", categoryId));
        return convertToResponse(category);
    }

    public List<CategoryAssessmentResponse> listByAssessment(Long assessmentId) {
        log.info("查询评估下的所有类别: assessmentId={}", assessmentId);

        if (!assessmentRepository.existsById(assessmentId)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId);
        }

        List<CategoryAssessment> categories = categoryAssessmentRepository.findByAssessmentIdOrderByCreatedAtAsc(assessmentId);
        return categories.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryAssessmentResponse updateCategory(Long categoryId, UpdateCategoryRequest request) {
        log.info("更新类别: id={}", categoryId);

        CategoryAssessment category = categoryAssessmentRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("CategoryAssessment", "id", categoryId));

        if (request.getCategoryName() != null) {
            category.setCategoryName(request.getCategoryName());
        }
        if (request.getCategoryNameEn() != null) {
            category.setCategoryNameEn(request.getCategoryNameEn());
        }
        if (request.getCategoryType() != null) {
            category.setCategoryType(request.getCategoryType());
        }
        if (request.getSceneDescription() != null) {
            category.setSceneDescription(request.getSceneDescription());
        }
        if (request.getViewAngle() != null) {
            category.setViewAngle(request.getViewAngle());
        }
        if (request.getEnvironmentConstraints() != null) {
            category.setEnvironmentConstraints(request.getEnvironmentConstraints());
        }
        if (request.getPerformanceRequirements() != null) {
            category.setPerformanceRequirements(request.getPerformanceRequirements());
        }

        CategoryAssessment updatedCategory = categoryAssessmentRepository.save(category);
        log.info("类别更新成功: id={}", categoryId);

        return convertToResponse(updatedCategory);
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        log.info("删除类别: id={}", categoryId);

        if (!categoryAssessmentRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("CategoryAssessment", "id", categoryId);
        }

        categoryAssessmentRepository.deleteById(categoryId);
        log.info("类别删除成功: id={}", categoryId);
    }

    private CategoryAssessmentResponse convertToResponse(CategoryAssessment category) {
        return CategoryAssessmentResponse.builder()
                .id(category.getId())
                .assessmentId(category.getAssessment() != null ? category.getAssessment().getId() : null)
                .categoryName(category.getCategoryName())
                .categoryNameEn(category.getCategoryNameEn())
                .categoryType(category.getCategoryType())
                .sceneDescription(category.getSceneDescription())
                .viewAngle(category.getViewAngle())
                .environmentConstraints(category.getEnvironmentConstraints())
                .performanceRequirements(category.getPerformanceRequirements())
                .feasibilityBucket(category.getFeasibilityBucket())
                .confidence(category.getConfidence())
                .reasoning(category.getReasoning())
                .createdAt(category.getCreatedAt())
                .build();
    }
}
