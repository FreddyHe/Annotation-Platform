package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.TrainingStartRequest;
import com.annotation.platform.entity.InferenceDataPoint;
import com.annotation.platform.entity.IterationRound;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.InferenceDataPointRepository;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.RoundService;
import com.annotation.platform.service.TrainingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/rounds")
public class RoundController {

    private final RoundService roundService;
    private final TrainingService trainingService;
    private final com.annotation.platform.service.FormatConverterService formatConverterService;
    private final ModelTrainingRecordRepository trainingRecordRepository;
    private final InferenceDataPointRepository inferenceDataPointRepository;
    private final ProjectRepository projectRepository;

    @GetMapping
    public Result<List<Map<String, Object>>> listRounds(@PathVariable Long projectId) {
        return Result.success(roundService.listRounds(projectId).stream()
                .map(roundService::toResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/current")
    public Result<Map<String, Object>> currentRound(@PathVariable Long projectId) {
        return Result.success(roundService.toResponse(roundService.currentRound(projectId)));
    }

    @PostMapping("/close-current")
    public Result<Map<String, Object>> closeCurrentRound(@PathVariable Long projectId) {
        IterationRound next = roundService.closeCurrentRound(projectId);
        return Result.success(roundService.toResponse(next));
    }

    @GetMapping("/{roundId}/training-preview")
    public Result<Map<String, Object>> trainingPreview(@PathVariable Long projectId, @PathVariable Long roundId) {
        return Result.success(roundService.trainingPreview(projectId, roundId));
    }

    @PostMapping("/{roundId}/trigger-retrain")
    @Transactional
    public Result<Map<String, Object>> triggerRetrain(
            @PathVariable Long projectId,
            @PathVariable Long roundId,
            @RequestBody(required = false) TrainingStartRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
            if (request == null) {
                request = new TrainingStartRequest();
            }
            User user = (User) httpRequest.getAttribute("user");
            if (user == null) {
                return Result.error("401", "Unauthorized");
            }
            Map<String, Object> preview = roundService.trainingPreview(projectId, roundId);
            Object sourceRoundIdValue = preview.get("sourceRoundId");
            Long sourceRoundId = sourceRoundIdValue == null ? roundId : Long.parseLong(sourceRoundIdValue.toString());

            com.annotation.platform.service.FormatConverterService.DatasetConversionResult dataset =
                    formatConverterService.buildFeedbackDataset(projectId, sourceRoundId, user.getLsToken());

            ModelTrainingRecord record = trainingService.startTrainingFromDataset(
                    user.getId(),
                    projectId,
                    roundId,
                    dataset.getOutputPath(),
                    dataset,
                    defaultInt(request.getEpochs(), 100),
                    defaultInt(request.getBatchSize(), 16),
                    defaultInt(request.getImageSize(), 640),
                    defaultString(request.getModelType(), "yolov8n"),
                    defaultString(request.getDevice(), "0"),
                    ModelTrainingRecord.TrainingDataSource.FEEDBACK
            );
            record.setRoundId(roundId);
            record.setTrainingDataSource(ModelTrainingRecord.TrainingDataSource.FEEDBACK);
            record = trainingRecordRepository.save(record);
            inferenceDataPointRepository.markUsedInRound(
                    sourceRoundId,
                    roundId,
                    List.of(InferenceDataPoint.PoolType.HIGH, InferenceDataPoint.PoolType.LOW_A, InferenceDataPoint.PoolType.LOW_B)
            );

            return Result.success(Map.of(
                    "trainingRecordId", record.getId(),
                    "taskId", record.getTaskId(),
                    "roundId", roundId,
                    "datasetPath", dataset.getOutputPath(),
                    "totalImages", dataset.getTrainImages() + dataset.getValImages(),
                    "totalAnnotations", dataset.getTotalAnnotations()
            ));
        } catch (Exception e) {
            log.error("Failed to trigger retrain", e);
            return Result.error("500", "Failed to trigger retrain: " + e.getMessage());
        }
    }

    private Integer defaultInt(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
