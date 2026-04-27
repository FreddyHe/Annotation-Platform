package com.annotation.platform.service;

import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.IterationRound;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.Project;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.IterationRoundRepository;
import com.annotation.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoundService {

    private final IterationRoundRepository iterationRoundRepository;
    private final ProjectRepository projectRepository;
    private final ProjectConfigService projectConfigService;
    private final InferenceDataPointRepository inferenceDataPointRepository;

    @Transactional
    public IterationRound ensureCurrentRound(Project project) {
        if (project.getCurrentRoundId() != null) {
            return iterationRoundRepository.findById(project.getCurrentRoundId())
                    .orElseGet(() -> createRound(project, nextRoundNumber(project.getId()), IterationRound.RoundStatus.ACTIVE));
        }
        IterationRound round = createRound(project, nextRoundNumber(project.getId()), IterationRound.RoundStatus.ACTIVE);
        project.setCurrentRoundId(round.getId());
        project.setProjectType(Project.ProjectType.ITERATIVE);
        projectRepository.save(project);
        projectConfigService.getOrCreate(project.getId());
        return round;
    }

    @Transactional
    public IterationRound createRound(Project project, Integer roundNumber, IterationRound.RoundStatus status) {
        IterationRound round = iterationRoundRepository.save(IterationRound.builder()
                .project(project)
                .roundNumber(roundNumber)
                .status(status)
                .startedAt(LocalDateTime.now())
                .build());
        projectConfigService.getOrCreate(project.getId());
        return round;
    }

    public Integer nextRoundNumber(Long projectId) {
        Integer max = iterationRoundRepository.findMaxRoundNumber(projectId);
        return (max == null ? 0 : max) + 1;
    }

    @Transactional
    public void markRoundTraining(Long roundId) {
        updateStatus(roundId, IterationRound.RoundStatus.TRAINING);
    }

    @Transactional
    public void markRoundTrainingCompleted(Long roundId, Long trainingRecordId) {
        IterationRound round = getRound(roundId);
        round.setTrainingRecordId(trainingRecordId);
        round.setStatus(IterationRound.RoundStatus.DEPLOYED_READY);
        iterationRoundRepository.save(round);
    }

    @Transactional
    public void markRoundDeployed(Long roundId) {
        updateStatus(roundId, IterationRound.RoundStatus.DEPLOYED);
    }

    @Transactional
    public void markRoundCollecting(Long roundId) {
        updateStatus(roundId, IterationRound.RoundStatus.COLLECTING);
    }

    @Transactional
    public void markRoundReviewing(Long roundId) {
        updateStatus(roundId, IterationRound.RoundStatus.REVIEWING);
    }

    @Transactional
    public IterationRound closeCurrentRound(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        IterationRound current = getRound(project.getCurrentRoundId());
        long unreviewed = inferenceDataPointRepository.countByRoundIdAndPoolTypeAndHumanReviewedFalse(
                current.getId(), InferenceDataPoint.PoolType.LOW_B);
        if (unreviewed > 0) {
            throw new BusinessException("还有 " + unreviewed + " 条低-B 数据未审核，请先完成审核");
        }
        current.setStatus(IterationRound.RoundStatus.CLOSED);
        current.setClosedAt(LocalDateTime.now());
        iterationRoundRepository.save(current);

        IterationRound next = createRound(project, current.getRoundNumber() + 1, IterationRound.RoundStatus.ACTIVE);
        project.setCurrentRoundId(next.getId());
        project.setProjectType(Project.ProjectType.ITERATIVE);
        projectRepository.save(project);
        return next;
    }

    @Transactional
    public IterationRound openDeploymentRound(Project project, Long modelRecordId, String notes) {
        if (project.getCurrentRoundId() != null) {
            IterationRound current = getRound(project.getCurrentRoundId());
            if (current.getStatus() != IterationRound.RoundStatus.CLOSED) {
                current.setStatus(IterationRound.RoundStatus.CLOSED);
                current.setClosedAt(LocalDateTime.now());
                iterationRoundRepository.save(current);
            }
        }

        IterationRound next = createRound(project, nextRoundNumber(project.getId()), IterationRound.RoundStatus.ACTIVE);
        next.setTrainingRecordId(modelRecordId);
        next.setNotes(notes);
        next = iterationRoundRepository.save(next);
        project.setCurrentRoundId(next.getId());
        project.setProjectType(Project.ProjectType.ITERATIVE);
        projectRepository.save(project);
        return next;
    }

    public List<IterationRound> listRounds(Long projectId) {
        return iterationRoundRepository.findByProjectIdOrderByRoundNumberDesc(projectId);
    }

    public IterationRound currentRound(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        return ensureCurrentRound(project);
    }

    public IterationRound getRound(Long roundId) {
        if (roundId == null) {
            throw new BusinessException("项目尚未创建迭代轮次");
        }
        return iterationRoundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("IterationRound", "id", roundId));
    }

    public Map<String, Object> trainingPreview(Long projectId, Long roundId) {
        IterationRound target = getRound(roundId);
        int previousRoundNumber = Math.max(1, target.getRoundNumber() - 1);
        IterationRound previous = iterationRoundRepository.findByProjectIdAndRoundNumber(projectId, previousRoundNumber)
                .orElse(target);
        long high = inferenceDataPointRepository.countByRoundIdAndPoolType(previous.getId(), InferenceDataPoint.PoolType.HIGH);
        long lowA = inferenceDataPointRepository.countByRoundIdAndPoolType(previous.getId(), InferenceDataPoint.PoolType.LOW_A);
        long lowB = inferenceDataPointRepository.findByRoundIdAndPoolTypeAndHumanReviewed(previous.getId(), InferenceDataPoint.PoolType.LOW_B, true).size();
        Map<String, Object> preview = new HashMap<>();
        preview.put("sourceRoundId", previous.getId());
        preview.put("targetRoundId", target.getId());
        preview.put("highPoolData", high);
        preview.put("lowAPoolData", lowA);
        preview.put("lowBReviewedData", lowB);
        preview.put("total", high + lowA + lowB);
        return preview;
    }

    public Map<String, Object> toResponse(IterationRound round) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", round.getId());
        response.put("projectId", round.getProject() != null ? round.getProject().getId() : null);
        response.put("roundNumber", round.getRoundNumber());
        response.put("status", round.getStatus() != null ? round.getStatus().name() : null);
        response.put("trainingRecordId", round.getTrainingRecordId());
        response.put("startedAt", round.getStartedAt());
        response.put("closedAt", round.getClosedAt());
        response.put("notes", round.getNotes());
        return response;
    }

    private void updateStatus(Long roundId, IterationRound.RoundStatus status) {
        IterationRound round = getRound(roundId);
        round.setStatus(status);
        iterationRoundRepository.save(round);
    }
}
