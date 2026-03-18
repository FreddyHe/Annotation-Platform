package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateOvdTestResultRequest;
import com.annotation.platform.dto.response.feasibility.OvdTestResultResponse;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.OvdTestResult;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.OvdTestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OvdTestResultService {

    private final OvdTestResultRepository ovdTestResultRepository;
    private final FeasibilityAssessmentRepository assessmentRepository;

    @Transactional
    public OvdTestResultResponse create(Long assessmentId, CreateOvdTestResultRequest request) {
        log.info("创建 OVD 测试结果: assessmentId={}, categoryName={}", assessmentId, request.getCategoryName());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        OvdTestResult result = OvdTestResult.builder()
                .assessment(assessment)
                .categoryName(request.getCategoryName())
                .imagePath(request.getImagePath())
                .promptUsed(request.getPromptUsed())
                .detectedCount(request.getDetectedCount())
                .averageConfidence(request.getAverageConfidence())
                .bboxJson(request.getBboxJson())
                .annotatedImagePath(request.getAnnotatedImagePath())
                .build();

        OvdTestResult saved = ovdTestResultRepository.save(result);
        log.info("OVD 测试结果创建成功: id={}, assessmentId={}", saved.getId(), assessmentId);
        return toResponse(saved);
    }

    public List<OvdTestResultResponse> listByAssessment(Long assessmentId) {
        log.info("查询评估下所有 OVD 测试结果: assessmentId={}", assessmentId);
        if (!assessmentRepository.existsById(assessmentId)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId);
        }
        return ovdTestResultRepository.findByAssessmentIdOrderByTestTimeDesc(assessmentId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public OvdTestResultResponse getById(Long id) {
        log.info("查询 OVD 测试结果详情: id={}", id);
        OvdTestResult result = ovdTestResultRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OvdTestResult", "id", id));
        return toResponse(result);
    }

    @Transactional
    public void delete(Long id) {
        log.info("删除 OVD 测试结果: id={}", id);
        if (!ovdTestResultRepository.existsById(id)) {
            throw new ResourceNotFoundException("OvdTestResult", "id", id);
        }
        ovdTestResultRepository.deleteById(id);
        log.info("删除 OVD 测试结果成功: id={}", id);
    }

    private OvdTestResultResponse toResponse(OvdTestResult result) {
        return OvdTestResultResponse.builder()
                .id(result.getId())
                .assessmentId(result.getAssessment() != null ? result.getAssessment().getId() : null)
                .categoryName(result.getCategoryName())
                .imagePath(result.getImagePath())
                .promptUsed(result.getPromptUsed())
                .detectedCount(result.getDetectedCount())
                .averageConfidence(result.getAverageConfidence())
                .bboxJson(result.getBboxJson())
                .annotatedImagePath(result.getAnnotatedImagePath())
                .testTime(result.getTestTime())
                .build();
    }
}
