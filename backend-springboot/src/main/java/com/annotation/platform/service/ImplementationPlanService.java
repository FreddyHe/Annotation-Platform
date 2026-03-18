package com.annotation.platform.service;

import com.annotation.platform.dto.request.feasibility.CreateImplementationPlanRequest;
import com.annotation.platform.dto.request.feasibility.UpdateImplementationPlanRequest;
import com.annotation.platform.dto.response.feasibility.ImplementationPlanResponse;
import com.annotation.platform.entity.FeasibilityAssessment;
import com.annotation.platform.entity.ImplementationPlan;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.FeasibilityAssessmentRepository;
import com.annotation.platform.repository.ImplementationPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImplementationPlanService {

    private final ImplementationPlanRepository implementationPlanRepository;
    private final FeasibilityAssessmentRepository assessmentRepository;

    @Transactional
    public ImplementationPlanResponse create(Long assessmentId, CreateImplementationPlanRequest request) {
        log.info("创建实施计划阶段: assessmentId={}, phaseOrder={}, phaseName={}", assessmentId, request.getPhaseOrder(), request.getPhaseName());

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        ImplementationPlan entity = ImplementationPlan.builder()
                .assessment(assessment)
                .phaseOrder(request.getPhaseOrder())
                .phaseName(request.getPhaseName())
                .description(request.getDescription())
                .estimatedDays(request.getEstimatedDays())
                .tasks(request.getTasks())
                .deliverables(request.getDeliverables())
                .dependencies(request.getDependencies())
                .build();

        ImplementationPlan saved = implementationPlanRepository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    public List<ImplementationPlanResponse> batchCreate(Long assessmentId, List<CreateImplementationPlanRequest> requests) {
        log.info("批量创建实施计划阶段: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);

        FeasibilityAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId));

        List<ImplementationPlan> entities = requests.stream()
                .map(req -> ImplementationPlan.builder()
                        .assessment(assessment)
                        .phaseOrder(req.getPhaseOrder())
                        .phaseName(req.getPhaseName())
                        .description(req.getDescription())
                        .estimatedDays(req.getEstimatedDays())
                        .tasks(req.getTasks())
                        .deliverables(req.getDeliverables())
                        .dependencies(req.getDependencies())
                        .build())
                .collect(Collectors.toList());

        List<ImplementationPlan> saved = implementationPlanRepository.saveAll(entities);
        return saved.stream()
                .map(this::toResponse)
                .sorted((a, b) -> {
                    if (a.getPhaseOrder() == null && b.getPhaseOrder() == null) {
                        return 0;
                    }
                    if (a.getPhaseOrder() == null) {
                        return 1;
                    }
                    if (b.getPhaseOrder() == null) {
                        return -1;
                    }
                    return a.getPhaseOrder().compareTo(b.getPhaseOrder());
                })
                .collect(Collectors.toList());
    }

    public List<ImplementationPlanResponse> list(Long assessmentId) {
        log.info("查询实施计划阶段列表: assessmentId={}", assessmentId);
        if (!assessmentRepository.existsById(assessmentId)) {
            throw new ResourceNotFoundException("FeasibilityAssessment", "id", assessmentId);
        }

        List<ImplementationPlan> results = implementationPlanRepository.findByAssessmentIdOrderByPhaseOrderAsc(assessmentId);
        return results.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public ImplementationPlanResponse getById(Long id) {
        log.info("查询实施计划阶段详情: id={}", id);
        ImplementationPlan result = implementationPlanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ImplementationPlan", "id", id));
        return toResponse(result);
    }

    @Transactional
    public ImplementationPlanResponse update(Long id, UpdateImplementationPlanRequest request) {
        log.info("更新实施计划阶段: id={}", id);
        ImplementationPlan plan = implementationPlanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ImplementationPlan", "id", id));

        if (request.getPhaseOrder() != null) {
            plan.setPhaseOrder(request.getPhaseOrder());
        }
        if (request.getPhaseName() != null) {
            plan.setPhaseName(request.getPhaseName());
        }
        if (request.getDescription() != null) {
            plan.setDescription(request.getDescription());
        }
        if (request.getEstimatedDays() != null) {
            plan.setEstimatedDays(request.getEstimatedDays());
        }
        if (request.getTasks() != null) {
            plan.setTasks(request.getTasks());
        }
        if (request.getDeliverables() != null) {
            plan.setDeliverables(request.getDeliverables());
        }
        if (request.getDependencies() != null) {
            plan.setDependencies(request.getDependencies());
        }

        ImplementationPlan saved = implementationPlanRepository.save(plan);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        log.info("删除实施计划阶段: id={}", id);
        if (!implementationPlanRepository.existsById(id)) {
            throw new ResourceNotFoundException("ImplementationPlan", "id", id);
        }
        implementationPlanRepository.deleteById(id);
    }

    private ImplementationPlanResponse toResponse(ImplementationPlan plan) {
        return ImplementationPlanResponse.builder()
                .id(plan.getId())
                .assessmentId(plan.getAssessment() != null ? plan.getAssessment().getId() : null)
                .phaseOrder(plan.getPhaseOrder())
                .phaseName(plan.getPhaseName())
                .description(plan.getDescription())
                .estimatedDays(plan.getEstimatedDays())
                .tasks(plan.getTasks())
                .deliverables(plan.getDeliverables())
                .dependencies(plan.getDependencies())
                .createdAt(plan.getCreatedAt())
                .build();
    }
}
