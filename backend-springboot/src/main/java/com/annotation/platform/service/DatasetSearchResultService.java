package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateDatasetSearchRequest;
import com.annotation.platform.dto.response.feasibility.DatasetSearchResultResponse;
import com.annotation.platform.entity.DatasetSearchResult;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.DatasetSearchResultRepository;
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
public class DatasetSearchResultService {

    private final DatasetSearchResultRepository datasetRepository;
    private final FeasibilityAssessmentRepository assessmentRepository;

    @Transactional
    public DatasetSearchResultResponse create(Long assessmentId, CreateDatasetSearchRequest request) {
        log.info("创建数据集搜索结果: assessmentId={}, datasetName={}", assessmentId, request.getDatasetName());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        DatasetSearchResult entity = DatasetSearchResult.builder()
                .assessment(assessment)
                .categoryName(request.getCategoryName())
                .source(request.getSource())
                .datasetName(request.getDatasetName())
                .datasetUrl(request.getDatasetUrl())
                .sampleCount(request.getSampleCount())
                .categories(request.getCategories())
                .annotationFormat(request.getAnnotationFormat())
                .license(request.getLicense())
                .relevanceScore(request.getRelevanceScore())
                .build();

        DatasetSearchResult saved = datasetRepository.save(entity);
        log.info("数据集搜索结果创建成功: id={}, assessmentId={}", saved.getId(), assessmentId);
        return toResponse(saved);
    }

    @Transactional
    public List<DatasetSearchResultResponse> batchCreate(Long assessmentId, List<CreateDatasetSearchRequest> requests) {
        log.info("批量创建数据集搜索结果: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<DatasetSearchResult> entities = requests.stream().map(req -> DatasetSearchResult.builder()
                .assessment(assessment)
                .categoryName(req.getCategoryName())
                .source(req.getSource())
                .datasetName(req.getDatasetName())
                .datasetUrl(req.getDatasetUrl())
                .sampleCount(req.getSampleCount())
                .categories(req.getCategories())
                .annotationFormat(req.getAnnotationFormat())
                .license(req.getLicense())
                .relevanceScore(req.getRelevanceScore())
                .build()).collect(Collectors.toList());

        List<DatasetSearchResult> saved = datasetRepository.saveAll(entities);
        return saved.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<DatasetSearchResultResponse> list(Long assessmentId, String categoryName, String source) {
        log.info("查询评估下所有数据集: assessmentId={}, categoryName={}, source={}", assessmentId, categoryName, source);
        if (!assessmentRepository.existsById(assessmentId)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId);
        }
        List<DatasetSearchResult> results = datasetRepository.findByAssessmentIdOrderByRelevanceScoreDesc(assessmentId);
        if (categoryName != null && !categoryName.isEmpty()) {
            results = results.stream()
                    .filter(r -> categoryName.equals(r.getCategoryName()))
                    .collect(Collectors.toList());
        }
        if (source != null && !source.isEmpty()) {
            results = results.stream()
                    .filter(r -> source.equals(r.getSource()))
                    .collect(Collectors.toList());
        }
        return results.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public DatasetSearchResultResponse getById(Long id) {
        log.info("查询数据集搜索结果详情: id={}", id);
        DatasetSearchResult result = datasetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DatasetSearchResult", "id", id));
        return toResponse(result);
    }

    @Transactional
    public void delete(Long id) {
        log.info("删除数据集搜索结果: id={}", id);
        if (!datasetRepository.existsById(id)) {
            throw new ResourceNotFoundException("DatasetSearchResult", "id", id);
        }
        datasetRepository.deleteById(id);
        log.info("删除数据集搜索结果成功: id={}", id);
    }

    private DatasetSearchResultResponse toResponse(DatasetSearchResult r) {
        return DatasetSearchResultResponse.builder()
                .id(r.getId())
                .assessmentId(r.getAssessment() != null ? r.getAssessment().getId() : null)
                .categoryName(r.getCategoryName())
                .searchUrl(r.getSearchUrl())
                .source(r.getSource())
                .datasetName(r.getDatasetName())
                .datasetUrl(r.getDatasetUrl())
                .sampleCount(r.getSampleCount())
                .categories(r.getCategories())
                .annotationFormat(r.getAnnotationFormat())
                .license(r.getLicense())
                .relevanceScore(r.getRelevanceScore())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
