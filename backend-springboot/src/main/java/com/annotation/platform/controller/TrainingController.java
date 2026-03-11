package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.TrainingStartRequest;
import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.User;
import com.annotation.platform.service.TrainingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {

    @Autowired
    private TrainingService trainingService;

    @PostMapping("/start")
    public Result<ModelTrainingRecord> startTraining(
            @RequestBody TrainingStartRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
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
        } catch (Exception e) {
            log.error("Failed to start training", e);
            return Result.error("500", "Failed to start training: " + e.getMessage());
        }
    }

    @GetMapping("/record/{id}")
    public Result<ModelTrainingRecord> getTrainingRecord(@PathVariable Long id) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecord(id);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            return Result.success(record);
        } catch (Exception e) {
            log.error("Failed to get training record", e);
            return Result.error("500", "Failed to get training record: " + e.getMessage());
        }
    }

    @GetMapping("/record/task/{taskId}")
    public Result<ModelTrainingRecord> getTrainingRecordByTaskId(@PathVariable String taskId) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingRecordByTaskId(taskId);
            if (record == null) {
                return Result.error("404", "Training record not found");
            }
            return Result.success(record);
        } catch (Exception e) {
            log.error("Failed to get training record", e);
            return Result.error("500", "Failed to get training record: " + e.getMessage());
        }
    }

    @GetMapping("/project/{projectId}")
    public Result<List<ModelTrainingRecord>> getTrainingRecordsByProject(@PathVariable Long projectId) {
        try {
            List<ModelTrainingRecord> records = trainingService.getTrainingRecordsByProject(projectId);
            return Result.success(records);
        } catch (Exception e) {
            log.error("Failed to get training records", e);
            return Result.error("500", "Failed to get training records: " + e.getMessage());
        }
    }

    @GetMapping("/user")
    public Result<List<ModelTrainingRecord>> getTrainingRecordsByUser(HttpServletRequest httpRequest) {
        try {
            User user = (User) httpRequest.getAttribute("user");
            if (user == null) {
                return Result.error("401", "Unauthorized");
            }

            List<ModelTrainingRecord> records = trainingService.getTrainingRecordsByUser(user.getId());
            return Result.success(records);
        } catch (Exception e) {
            log.error("Failed to get training records", e);
            return Result.error("500", "Failed to get training records: " + e.getMessage());
        }
    }

    @GetMapping("/log/{taskId}")
    public Result<Map<String, Object>> getTrainingLog(@PathVariable String taskId) {
        try {
            String logContent = trainingService.getTrainingLog(taskId);

            Map<String, Object> result = new HashMap<>();
            result.put("task_id", taskId);
            result.put("log_content", logContent);

            return Result.success(result);
        } catch (Exception e) {
            log.error("Failed to get training log", e);
            return Result.error("500", "Failed to get training log: " + e.getMessage());
        }
    }

    @PostMapping("/cancel/{id}")
    public Result<Void> cancelTraining(@PathVariable Long id) {
        try {
            trainingService.cancelTraining(id);
            return Result.success(null);
        } catch (Exception e) {
            log.error("Failed to cancel training", e);
            return Result.error("500", "Failed to cancel training: " + e.getMessage());
        }
    }

    @GetMapping("/results/{taskId}")
    public Result<ModelTrainingRecord> getTrainingResults(@PathVariable String taskId) {
        try {
            ModelTrainingRecord record = trainingService.getTrainingResults(taskId);
            return Result.success(record);
        } catch (Exception e) {
            log.error("Failed to get training results", e);
            return Result.error("500", "Failed to get training results: " + e.getMessage());
        }
    }

    @GetMapping("/completed")
    public Result<List<ModelTrainingRecord>> getCompletedTrainings() {
        try {
            List<ModelTrainingRecord> records = trainingService.getCompletedTrainingsOrderByMap50Desc();
            return Result.success(records);
        } catch (Exception e) {
            log.error("Failed to get completed trainings", e);
            return Result.error("500", "Failed to get completed trainings: " + e.getMessage());
        }
    }
}
