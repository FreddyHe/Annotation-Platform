package com.annotation.platform.service;

import com.annotation.platform.entity.ModelTrainingRecord;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.ModelTrainingRecordRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class TrainingService {

    @Autowired
    private ModelTrainingRecordRepository trainingRecordRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FormatConverterService formatConverterService;

    @Autowired
    private LabelStudioProxyService labelStudioProxyService;

    @Value("${algorithm.service.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${training.output-base-path:/root/autodl-fs/Annotation-Platform/training_runs}")
    private String trainingOutputBasePath;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModelTrainingRecord startTraining(
            Long userId,
            Long projectId,
            String labelStudioProjectId,
            String lsToken,
            Integer epochs,
            Integer batchSize,
            Integer imageSize,
            String modelType,
            String device
    ) throws Exception {
        log.info("Starting training for project {} by user {}", projectId, userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String outputDir = trainingOutputBasePath + "/project_" + projectId;
        String runName = "run_" + System.currentTimeMillis();

        FormatConverterService.DatasetConversionResult conversionResult =
                formatConverterService.convertLabelStudioToYOLO(projectId, labelStudioProjectId, lsToken, outputDir);

        String datasetPath = outputDir + "/data.yaml";

        Map<String, Object> trainRequest = new HashMap<>();
        trainRequest.put("project_id", projectId);
        trainRequest.put("dataset_path", datasetPath);
        trainRequest.put("epochs", epochs);
        trainRequest.put("batch_size", batchSize);
        trainRequest.put("image_size", imageSize);
        trainRequest.put("model_type", modelType);
        trainRequest.put("device", device);

        String trainUrl = algorithmServiceUrl + "/api/v1/algo/train/yolo";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(trainRequest, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(trainUrl, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start training: " + response.getStatusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        String taskId = responseJson.get("task_id").asText();

        ModelTrainingRecord record = ModelTrainingRecord.builder()
                .projectId(projectId)
                .userId(userId)
                .taskId(taskId)
                .runName(runName)
                .status(ModelTrainingRecord.TrainingStatus.RUNNING)
                .epochs(epochs)
                .batchSize(batchSize)
                .imageSize(imageSize)
                .modelType(modelType)
                .datasetPath(datasetPath)
                .outputDir(outputDir)
                .totalImages(conversionResult.getTrainImages() + conversionResult.getValImages())
                .totalAnnotations(conversionResult.getTotalAnnotations())
                .startedAt(LocalDateTime.now())
                .build();

        return trainingRecordRepository.save(record);
    }

    public ModelTrainingRecord getTrainingRecord(Long id) {
        return trainingRecordRepository.findById(id).orElse(null);
    }

    public ModelTrainingRecord getTrainingRecordByTaskId(String taskId) {
        return trainingRecordRepository.findByTaskId(taskId).orElse(null);
    }

    public List<ModelTrainingRecord> getTrainingRecordsByProject(Long projectId) {
        return trainingRecordRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<ModelTrainingRecord> getTrainingRecordsByUser(Long userId) {
        return trainingRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<ModelTrainingRecord> getRunningTrainings() {
        return trainingRecordRepository.findByStatus(ModelTrainingRecord.TrainingStatus.RUNNING);
    }

    public String getTrainingLog(String taskId) throws Exception {
        String logUrl = algorithmServiceUrl + "/api/v1/algo/train/log/" + taskId;

        ResponseEntity<String> response = restTemplate.getForEntity(logUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get training log: " + response.getStatusCode());
        }

        return response.getBody();
    }

    public void cancelTraining(Long id) throws Exception {
        ModelTrainingRecord record = trainingRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Training record not found"));

        if (record.getStatus() != ModelTrainingRecord.TrainingStatus.RUNNING) {
            throw new RuntimeException("Training is not running");
        }

        String cancelUrl = algorithmServiceUrl + "/api/v1/algo/train/cancel/" + record.getTaskId();
        restTemplate.postForEntity(cancelUrl, null, String.class);

        record.setStatus(ModelTrainingRecord.TrainingStatus.CANCELLED);
        record.setCompletedAt(LocalDateTime.now());
        trainingRecordRepository.save(record);
    }

    public ModelTrainingRecord getTrainingResults(String taskId) throws Exception {
        String resultsUrl = algorithmServiceUrl + "/api/v1/algo/train/results/" + taskId;

        ResponseEntity<String> response = restTemplate.getForEntity(resultsUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get training results: " + response.getStatusCode());
        }

        ModelTrainingRecord record = trainingRecordRepository.findByTaskId(taskId)
                .orElseThrow(() -> new RuntimeException("Training record not found"));

        JsonNode resultsJson = objectMapper.readTree(response.getBody());
        JsonNode results = resultsJson.get("results");

        if (results != null && results.isArray() && results.size() > 0) {
            JsonNode result = results.get(0);

            if (result.has("best_model_path")) {
                record.setBestModelPath(result.get("best_model_path").asText());
            }
            if (result.has("last_model_path")) {
                record.setLastModelPath(result.get("last_model_path").asText());
            }
            if (result.has("log_file")) {
                record.setLogFilePath(result.get("log_file").asText());
            }

            trainingRecordRepository.save(record);
        }

        return record;
    }

    @Scheduled(fixedDelay = 30000)
    public void updateTrainingStatus() {
        log.debug("Updating training status...");

        List<ModelTrainingRecord> runningTrainings = getRunningTrainings();

        for (ModelTrainingRecord record : runningTrainings) {
            try {
                String statusUrl = algorithmServiceUrl + "/api/v1/algo/train/status/" + record.getTaskId();
                ResponseEntity<String> response = restTemplate.getForEntity(statusUrl, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    JsonNode statusJson = objectMapper.readTree(response.getBody());
                    String status = statusJson.get("status").asText();

                    if ("COMPLETED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.COMPLETED);
                        record.setCompletedAt(LocalDateTime.now());

                        getTrainingResults(record.getTaskId());

                    } else if ("FAILED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.FAILED);
                        record.setCompletedAt(LocalDateTime.now());

                        if (statusJson.has("error")) {
                            record.setErrorMessage(statusJson.get("error").asText());
                        }

                    } else if ("CANCELLED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.CANCELLED);
                        record.setCompletedAt(LocalDateTime.now());
                    }

                    trainingRecordRepository.save(record);
                }
            } catch (Exception e) {
                log.error("Failed to update training status for task {}: {}", record.getTaskId(), e.getMessage());
            }
        }
    }

    public List<ModelTrainingRecord> getCompletedTrainingsOrderByMap50Desc() {
        return trainingRecordRepository.findCompletedOrderByMap50Desc();
    }
}
