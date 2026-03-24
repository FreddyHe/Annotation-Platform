package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateVlmQualityScoreRequest;
import com.annotation.platform.dto.response.feasibility.VlmQualityScoreResponse;
import com.annotation.platform.entity.OvdTestResult;
import com.annotation.platform.entity.VlmQualityScore;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.OvdTestResultRepository;
import com.annotation.platform.repository.VlmQualityScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VlmQualityScoreService {

    private final VlmQualityScoreRepository vlmQualityScoreRepository;
    private final OvdTestResultRepository ovdTestResultRepository;

    @Transactional
    public VlmQualityScoreResponse create(Long ovdResultId, CreateVlmQualityScoreRequest request) {
        log.info("创建 VLM 质量评分: ovdResultId={}", ovdResultId);

        OvdTestResult ovdResult = ovdTestResultRepository.findById(ovdResultId)
                .orElseThrow(() -> new ResourceNotFoundException("OvdTestResult", "id", ovdResultId));

        VlmQualityScore score = VlmQualityScore.builder()
                .ovdTestResult(ovdResult)
                .totalGtEstimated(request.getTotalGtEstimated())
                .detected(request.getDetected())
                .falsePositive(request.getFalsePositive())
                .precisionEstimate(request.getPrecisionEstimate())
                .recallEstimate(request.getRecallEstimate())
                .bboxQuality(request.getBboxQuality())
                .overallVerdict(request.getOverallVerdict())
                .notes(request.getNotes())
                .build();

        VlmQualityScore saved = vlmQualityScoreRepository.save(score);
        log.info("VLM 质量评分创建成功: id={}, ovdResultId={}", saved.getId(), ovdResultId);
        return toResponse(saved);
    }

    public List<VlmQualityScoreResponse> listByOvdResult(Long ovdResultId) {
        log.info("查询 OVD 结果下所有质量评分: ovdResultId={}", ovdResultId);
        if (!ovdTestResultRepository.existsById(ovdResultId)) {
            throw new ResourceNotFoundException("OvdTestResult", "id", ovdResultId);
        }
        return vlmQualityScoreRepository.findByOvdTestResultIdOrderByCreatedAtDesc(ovdResultId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public VlmQualityScoreResponse getById(Long id) {
        log.info("查询质量评分详情: id={}", id);
        VlmQualityScore score = vlmQualityScoreRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("VlmQualityScore", "id", id));
        return toResponse(score);
    }

    private VlmQualityScoreResponse toResponse(VlmQualityScore score) {
        java.util.List<com.annotation.platform.dto.response.feasibility.VlmEvaluationDetailResponse> detailResponses = 
            score.getEvaluationDetails() != null 
            ? score.getEvaluationDetails().stream()
                .map(detail -> com.annotation.platform.dto.response.feasibility.VlmEvaluationDetailResponse.builder()
                    .id(detail.getId())
                    .bboxIdx(detail.getBboxIdx())
                    .isCorrect(detail.getIsCorrect())
                    .croppedImagePath(detail.getCroppedImagePath())
                    .question(detail.getQuestion())
                    .vlmAnswer(detail.getVlmAnswer())
                    .bboxJson(detail.getBboxJson())
                    .errorReason(detail.getErrorReason())
                    .build())
                .collect(Collectors.toList())
            : new java.util.ArrayList<>();
        
        return VlmQualityScoreResponse.builder()
                .id(score.getId())
                .ovdTestResultId(score.getOvdTestResult() != null ? score.getOvdTestResult().getId() : null)
                .totalGtEstimated(score.getTotalGtEstimated())
                .detected(score.getDetected())
                .falsePositive(score.getFalsePositive())
                .precisionEstimate(score.getPrecisionEstimate())
                .recallEstimate(score.getRecallEstimate())
                .bboxQuality(score.getBboxQuality())
                .overallVerdict(score.getOverallVerdict())
                .notes(score.getNotes())
                .createdAt(score.getCreatedAt())
                .evaluationDetails(detailResponses)
                .build();
    }
}
