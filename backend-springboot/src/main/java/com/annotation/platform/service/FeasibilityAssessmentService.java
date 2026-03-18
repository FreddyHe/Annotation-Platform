package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateAssessmentRequest;
import com.annotation.platform.dto.response.feasibility.FeasibilityAssessmentResponse;
import com.annotation.platform.entity.AssessmentStatus;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeasibilityAssessmentService {

    private final FeasibilityAssessmentRepository assessmentRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    public FeasibilityAssessmentResponse createAssessment(CreateAssessmentRequest request, Long userId, Long organizationId) {
        log.info("创建可行性评估: name={}, userId={}, orgId={}", request.getAssessmentName(), userId, organizationId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", organizationId));

        FeasibilityAssessment assessment = FeasibilityAssessment.builder()
                .assessmentName(request.getAssessmentName())
                .rawRequirement(request.getRawRequirement())
                .status(AssessmentStatus.CREATED)
                .createdBy(user)
                .organization(organization)
                .build();

        FeasibilityAssessment savedAssessment = assessmentRepository.save(assessment);
        log.info("可行性评估创建成功: id={}", savedAssessment.getId());

        return convertToResponse(savedAssessment);
    }

    public FeasibilityAssessmentResponse getAssessment(Long id) {
        log.info("查询可行性评估详情: id={}", id);
        FeasibilityAssessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", id));
        return convertToResponse(assessment);
    }

    public List<FeasibilityAssessmentResponse> listAssessments(Long organizationId) {
        log.info("查询可行性评估列表: orgId={}", organizationId);
        List<FeasibilityAssessment> assessments;
        if (organizationId != null) {
            assessments = assessmentRepository.findByOrganization_IdOrderByCreatedAtDesc(organizationId);
        } else {
            assessments = assessmentRepository.findAllByOrderByCreatedAtDesc();
        }
        return assessments.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public FeasibilityAssessmentResponse updateStatus(Long id, AssessmentStatus status) {
        log.info("更新可行性评估状态: id={}, status={}", id, status);
        FeasibilityAssessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", id));

        assessment.setStatus(status);

        if (status == AssessmentStatus.COMPLETED) {
            assessment.setCompletedAt(java.time.LocalDateTime.now());
        }

        FeasibilityAssessment updatedAssessment = assessmentRepository.save(assessment);
        log.info("可行性评估状态更新成功: id={}, newStatus={}", id, status);

        return convertToResponse(updatedAssessment);
    }

    @Transactional
    public void deleteAssessment(Long id) {
        log.info("删除可行性评估: id={}", id);
        if (!assessmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", id);
        }
        assessmentRepository.deleteById(id);
        log.info("可行性评估删除成功: id={}", id);
    }

    private FeasibilityAssessmentResponse convertToResponse(FeasibilityAssessment assessment) {
        return FeasibilityAssessmentResponse.builder()
                .id(assessment.getId())
                .assessmentName(assessment.getAssessmentName())
                .rawRequirement(assessment.getRawRequirement())
                .structuredRequirement(assessment.getStructuredRequirement())
                .status(assessment.getStatus())
                .createdAt(assessment.getCreatedAt())
                .updatedAt(assessment.getUpdatedAt())
                .completedAt(assessment.getCompletedAt())
                .createdBy(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getId() : null)
                .createdByUsername(assessment.getCreatedBy() != null ? assessment.getCreatedBy().getDisplayName() : null)
                .organizationId(assessment.getOrganization() != null ? assessment.getOrganization().getId() : null)
                .organizationName(assessment.getOrganization() != null ? assessment.getOrganization().getName() : null)
                .build();
    }
}