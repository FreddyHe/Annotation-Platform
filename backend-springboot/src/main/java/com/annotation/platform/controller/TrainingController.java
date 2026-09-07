package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.TrainingStartRequest;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.service.ProjectAccessService;
import com.annotation.platform.service.TrainingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/training")
public class TrainingController {

    @Autowired
    private TrainingService trainingService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private ModelTrainingRecordRepository modelTrainingRecordRepository;

    @PostMapping("/start")
    public Result<ModelTrainingRecord> startTraining(
            @RequestBody TrainingStartRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
            projectAccessService.requireProjectAccess(request.getProjectId(), httpRequest);
            User user = (User) httpRequest.getAttribute("user");
            if (user == null) {
                return Result.error("401", "Unauthorized");
            }

            ModelTrainingRecord record = trainingService.startTraining(
                    user.getId(),
                    request.getProjectId(),
                    request.getLabelStudioProjectId(),
                    request.getLsToken(),
                    request.getEpochs(),
                    request.getBatchSize(),
                    request.getImageSize(),
                    request.getModelType(),
                    request.getDevice()
            );

            return Result.success(record);
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to start training", e);
            return Result.error("500", "Failed to start training: " + e.getMessage());
        }
    }

    @GetMapping("/record/{id}")
    public Result<Map<String, Object>> getTrainingRecord(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecord(id);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            projectAccessService.requireTrainingRecordInCurrentOrg(record, httpRequest);
            return Result.success(toRecordResponse(record));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get training record", e);
            return Result.error("500", "Failed to get training record: " + e.getMessage());
        }
    }

    @GetMapping("/record/task/{taskId}")
    public Result<Map<String, Object>> getTrainingRecordByTaskId(@PathVariable String taskId, HttpServletRequest httpRequest) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecordByTaskId(taskId);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            projectAccessService.requireTrainingRecordInCurrentOrg(record, httpRequest);
            return Result.success(toRecordResponse(record));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get training record", e);
            return Result.error("500", "Failed to get training record: " + e.getMessage());
        }
    }

    @GetMapping("/project/{projectId}")
    public Result<List<Map<String, Object>>> getTrainingRecordsByProject(@PathVariable Long projectId, HttpServletRequest httpRequest) {
        try {
            projectAccessService.requireProjectAccess(projectId, httpRequest);
            List<ModelTrainingRecord> records = trainingService.getTrainingRecordsByProject(projectId);
            return Result.success(records.stream()
                    .map(trainingService::refreshTrainingRecord)
                    .map(this::toRecordResponse)
                    .collect(Collectors.toList()));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get training records", e);
            return Result.error("500", "Failed to get training records: " + e.getMessage());
        }
    }

    @GetMapping("/user")
    public Result<List<Map<String, Object>>> getTrainingRecordsByUser(HttpServletRequest httpRequest) {
        try {
            User user = (User) httpRequest.getAttribute("user");
            if (user == null) {
                return Result.error("401", "Unauthorized");
            }

            List<ModelTrainingRecord> records = trainingService.getTrainingRecordsByUser(user.getId());
            return Result.success(records.stream()
                    .map(trainingService::refreshTrainingRecord)
                    .map(this::toRecordResponse)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("Failed to get training records", e);
            return Result.error("500", "Failed to get training records: " + e.getMessage());
        }
    }

    @GetMapping("/log/{taskId}")
    public Result<Map<String, Object>> getTrainingLog(@PathVariable String taskId, HttpServletRequest httpRequest) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecordByTaskId(taskId);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            projectAccessService.requireTrainingRecordInCurrentOrg(record, httpRequest);
            String logContent = trainingService.getTrainingLog(taskId);

            Map<String, Object> result = new HashMap<>();
            result.put("task_id", taskId);
            result.put("log_content", logContent);

            return Result.success(result);
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get training log", e);
            return Result.error("500", "Failed to get training log: " + e.getMessage());
        }
    }

    @PostMapping("/cancel/{id}")
    public Result<Void> cancelTraining(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecord(id);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            projectAccessService.requireTrainingRecordInCurrentOrg(record, httpRequest);
            trainingService.cancelTraining(id);
            return Result.success(null);
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to cancel training", e);
            return Result.error("500", "Failed to cancel training: " + e.getMessage());
        }
    }

    @GetMapping("/results/{taskId}")
    public Result<Map<String, Object>> getTrainingResults(@PathVariable String taskId, HttpServletRequest httpRequest) {
        try {
            ModelTrainingRecord existing = trainingService.getTrainingRecordByTaskId(taskId);
            if (existing == null) {
                return Result.error("404", "Training record not found");
            }
            projectAccessService.requireTrainingRecordInCurrentOrg(existing, httpRequest);
            ModelTrainingRecord record = trainingService.getTrainingResults(taskId);
            return Result.success(toRecordResponse(record));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get training results", e);
            return Result.error("500", "Failed to get training results: " + e.getMessage());
        }
    }

    @GetMapping("/completed")
    public Result<List<Map<String, Object>>> getCompletedTrainings(HttpServletRequest httpRequest) {
        try {
            Long organizationId = projectAccessService.currentOrganizationId(httpRequest);
            List<ModelTrainingRecord> records = modelTrainingRecordRepository.findCompletedByOrganizationIdOrderByMap50Desc(organizationId);
            return Result.success(records.stream()
                    .map(trainingService::hydrateCompletedRecordFromOutput)
                    .map(this::toRecordResponse)
                    .collect(Collectors.toList()));
        } catch (org.springframework.security.access.AccessDeniedException | com.annotation.platform.exception.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get completed trainings", e);
            return Result.error("500", "Failed to get completed trainings: " + e.getMessage());
        }
    }

    private Map<String, Object> toRecordResponse(ModelTrainingRecord record) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", record.getId());
        response.put("projectId", record.getProjectId());
        response.put("userId", record.getUserId());
        response.put("taskId", record.getTaskId());
        response.put("runName", record.getRunName());
        response.put("status", record.getStatus() != null ? record.getStatus().name() : null);
        response.put("roundId", record.getRoundId());
        response.put("trainingDataSource", record.getTrainingDataSource() != null ? record.getTrainingDataSource().name() : null);
        response.put("epochs", record.getEpochs());
        response.put("batchSize", record.getBatchSize());
        response.put("imageSize", record.getImageSize());
        response.put("modelType", record.getModelType());
        response.put("datasetPath", record.getDatasetPath());
        response.put("outputDir", record.getOutputDir());
        response.put("bestModelPath", record.getBestModelPath());
        response.put("lastModelPath", record.getLastModelPath());
        response.put("logFilePath", record.getLogFilePath());
        response.put("map50", record.getMap50());
        response.put("map50_95", record.getMap50_95());
        response.put("precision", record.getPrecision());
        response.put("recall", record.getRecall());
        response.put("totalImages", record.getTotalImages());
        response.put("totalAnnotations", record.getTotalAnnotations());
        response.put("startedAt", record.getStartedAt());
        response.put("completedAt", record.getCompletedAt());
        response.put("errorMessage", record.getErrorMessage());
        response.put("testResults", parseJsonField(record.getTestResults()));
        response.put("createdAt", record.getCreatedAt());
        response.put("updatedAt", record.getUpdatedAt());
        return response;
    }

    private Object parseJsonField(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Object current = value;
        for (int i = 0; i < 8; i++) {
            if (!(current instanceof String text)) {
                return current;
            }
            try {
                current = com.alibaba.fastjson2.JSON.parse(text);
            } catch (Exception e) {
                return value;
            }
        }
        return current;
    }
}
