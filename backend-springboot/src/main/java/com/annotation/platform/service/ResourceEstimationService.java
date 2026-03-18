package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateResourceEstimationRequest;
import com.annotation.platform.dto.response.feasibility.ResourceEstimationResponse;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.ResourceEstimation;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.ResourceEstimationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceEstimationService {

    private final ResourceEstimationRepository resourceEstimationRepository;
    private final FeasibilityAssessmentRepository assessmentRepository;

    @Transactional
    public ResourceEstimationResponse create(Long assessmentId, CreateResourceEstimationRequest request) {
        log.info("创建资源估算: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        ResourceEstimation entity = ResourceEstimation.builder()
                .assessment(assessment)
                .categoryName(request.getCategoryName())
                .feasibilityBucket(request.getFeasibilityBucket())
                .estimatedImages(request.getEstimatedImages())
                .estimatedManDays(request.getEstimatedManDays())
                .gpuHours(request.getGpuHours())
                .iterationCount(request.getIterationCount())
                .estimatedTotalDays(request.getEstimatedTotalDays())
                .estimatedCost(request.getEstimatedCost())
                .notes(request.getNotes())
                .build();

        ResourceEstimation saved = resourceEstimationRepository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    public List<ResourceEstimationResponse> batchCreate(Long assessmentId, List<CreateResourceEstimationRequest> requests) {
        log.info("批量创建资源估算: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<ResourceEstimation> entities = requests.stream()
                .map(req -> ResourceEstimation.builder()
                        .assessment(assessment)
                        .categoryName(req.getCategoryName())
                        .feasibilityBucket(req.getFeasibilityBucket())
                        .estimatedImages(req.getEstimatedImages())
                        .estimatedManDays(req.getEstimatedManDays())
                        .gpuHours(req.getGpuHours())
                        .iterationCount(req.getIterationCount())
                        .estimatedTotalDays(req.getEstimatedTotalDays())
                        .estimatedCost(req.getEstimatedCost())
                        .notes(req.getNotes())
                        .build())
                .collect(Collectors.toList());

        List<ResourceEstimation> saved = resourceEstimationRepository.saveAll(entities);
        return saved.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ResourceEstimationResponse> list(Long assessmentId, String categoryName) {
        log.info("查询资源估算: assessmentId={}, categoryName={}", assessmentId, categoryName);
        if (!assessmentRepository.existsById(assessmentId)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId);
        }

        List<ResourceEstimation> results;
        if (categoryName != null && !categoryName.isEmpty()) {
            results = resourceEstimationRepository.findByAssessmentIdAndCategoryName(assessmentId, categoryName);
        } else {
            results = resourceEstimationRepository.findByAssessmentId(assessmentId);
        }

        results.sort(Comparator.comparing(ResourceEstimation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return results.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public ResourceEstimationResponse getById(Long id) {
        log.info("查询资源估算详情: id={}", id);
        ResourceEstimation result = resourceEstimationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ResourceEstimation", "id", id));
        return toResponse(result);
    }

    @Transactional
    public void delete(Long id) {
        log.info("删除资源估算: id={}", id);
        if (!resourceEstimationRepository.existsById(id)) {
            throw new ResourceNotFoundException("ResourceEstimation", "id", id);
        }
        resourceEstimationRepository.deleteById(id);
    }

    private ResourceEstimationResponse toResponse(ResourceEstimation r) {
        return ResourceEstimationResponse.builder()
                .id(r.getId())
                .assessmentId(r.getAssessment() != null ? r.getAssessment().getId() : null)
                .categoryName(r.getCategoryName())
                .feasibilityBucket(r.getFeasibilityBucket())
                .estimatedImages(r.getEstimatedImages())
                .estimatedManDays(r.getEstimatedManDays())
                .gpuHours(r.getGpuHours())
                .iterationCount(r.getIterationCount())
                .estimatedTotalDays(r.getEstimatedTotalDays())
                .estimatedCost(r.getEstimatedCost())
                .notes(r.getNotes())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
