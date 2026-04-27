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
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
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

    @Autowired
    private RoundService roundService;

    @Autowired
    private IncrementalProjectService incrementalProjectService;

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${training.output-base-path:/root/autodl-fs/Annotation-Platform/training_runs}")
    private String trainingOutputBasePath;

    private final RestTemplate restTemplate;
    private final RestTemplate statusRestTemplate = createStatusRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public TrainingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private static RestTemplate createStatusRestTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(5))
                .setConnectTimeout(Timeout.ofSeconds(3))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(3000);
        return new RestTemplate(requestFactory);
    }

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
        return startTraining(userId, projectId, labelStudioProjectId, lsToken, epochs, batchSize, imageSize, modelType, device,
                ModelTrainingRecord.TrainingDataSource.INITIAL, false);
    }

    public ModelTrainingRecord startTraining(
            Long userId,
            Long projectId,
            String labelStudioProjectId,
            String lsToken,
            Integer epochs,
            Integer batchSize,
            Integer imageSize,
            String modelType,
            String device,
            ModelTrainingRecord.TrainingDataSource trainingDataSource
    ) throws Exception {
        return startTraining(userId, projectId, labelStudioProjectId, lsToken, epochs, batchSize, imageSize, modelType, device,
                trainingDataSource, false);
    }

    public ModelTrainingRecord startTraining(
            Long userId,
            Long projectId,
            String labelStudioProjectId,
            String lsToken,
            Integer epochs,
            Integer batchSize,
            Integer imageSize,
            String modelType,
            String device,
            ModelTrainingRecord.TrainingDataSource trainingDataSource,
            boolean forceRetrain
    ) throws Exception {
        log.info("Starting training for project {} by user {}", projectId, userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        Long roundId = project.getCurrentRoundId();

        String runName = "run_" + System.currentTimeMillis();
        String outputDir = trainingOutputBasePath + "/project_" + projectId + "/dataset_" + runName;

        incrementalProjectService.syncPendingDataToLabelStudio(projectId, userId);
        incrementalProjectService.getIncrementalProjectsStatus(projectId, userId);

        FormatConverterService.DatasetConversionResult conversionResult =
                formatConverterService.buildFlywheelDataset(projectId, lsToken, outputDir);
        if (conversionResult.getTrainImages() + conversionResult.getValImages() <= 0) {
            throw new RuntimeException("没有可用于训练的数据：主项目为空，且没有已全审的增量项目");
        }
        conversionResult.getMetadata().put("trainImages", conversionResult.getTrainImages());
        conversionResult.getMetadata().put("valImages", conversionResult.getValImages());
        conversionResult.getMetadata().put("totalAnnotations", conversionResult.getTotalAnnotations());
        preventDuplicateDataset(projectId, conversionResult.getMetadata(), forceRetrain);

        Map<String, Object> trainRequest = new HashMap<>();
        trainRequest.put("project_id", projectId);
        trainRequest.put("dataset_path", outputDir);
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
                .roundId(roundId)
                .trainingDataSource(roundId != null ? trainingDataSource : null)
                .runName(runName)
                .status(ModelTrainingRecord.TrainingStatus.RUNNING)
                .epochs(epochs)
                .batchSize(batchSize)
                .imageSize(imageSize)
                .modelType(modelType)
                .datasetPath(outputDir + "/data.yaml")
                .outputDir(outputDir)
                .totalImages(conversionResult.getTrainImages() + conversionResult.getValImages())
                .totalAnnotations(conversionResult.getTotalAnnotations())
                .testResults(objectMapper.writeValueAsString(Map.of(
                        "datasetPolicy", "main Label Studio project + fully reviewed incremental projects",
                        "trainImages", conversionResult.getTrainImages(),
                        "valImages", conversionResult.getValImages(),
                        "totalAnnotations", conversionResult.getTotalAnnotations(),
                        "metadata", conversionResult.getMetadata()
                )))
                .startedAt(LocalDateTime.now())
                .build();

        record = trainingRecordRepository.save(record);
        if (roundId != null) {
            roundService.markRoundTraining(roundId);
        }
        return record;
    }

    public ModelTrainingRecord startTrainingFromDataset(
            Long userId,
            Long projectId,
            Long roundId,
            String datasetDir,
            FormatConverterService.DatasetConversionResult conversionResult,
            Integer epochs,
            Integer batchSize,
            Integer imageSize,
            String modelType,
            String device,
            ModelTrainingRecord.TrainingDataSource trainingDataSource
    ) throws Exception {
        log.info("Starting training from prepared dataset for project {} round {}", projectId, roundId);

        userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        projectRepository.findById(projectId).orElseThrow(() -> new RuntimeException("Project not found"));

        String runName = "run_" + System.currentTimeMillis();
        Map<String, Object> trainRequest = new HashMap<>();
        trainRequest.put("project_id", projectId);
        trainRequest.put("dataset_path", datasetDir);
        trainRequest.put("epochs", epochs);
        trainRequest.put("batch_size", batchSize);
        trainRequest.put("image_size", imageSize);
        trainRequest.put("model_type", modelType);
        trainRequest.put("device", device);

        String trainUrl = algorithmServiceUrl + "/api/v1/algo/train/yolo";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(trainUrl, new HttpEntity<>(trainRequest, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start training: " + response.getStatusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        String taskId = responseJson.get("task_id").asText();

        ModelTrainingRecord record = ModelTrainingRecord.builder()
                .projectId(projectId)
                .userId(userId)
                .taskId(taskId)
                .roundId(roundId)
                .trainingDataSource(trainingDataSource)
                .runName(runName)
                .status(ModelTrainingRecord.TrainingStatus.RUNNING)
                .epochs(epochs)
                .batchSize(batchSize)
                .imageSize(imageSize)
                .modelType(modelType)
                .datasetPath(Paths.get(datasetDir, "data.yaml").toString())
                .outputDir(datasetDir)
                .totalImages(conversionResult.getTrainImages() + conversionResult.getValImages())
                .totalAnnotations(conversionResult.getTotalAnnotations())
                .startedAt(LocalDateTime.now())
                .build();
        record = trainingRecordRepository.save(record);
        if (roundId != null) {
            roundService.markRoundTraining(roundId);
        }
        return record;
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
            applyTrainingResult(record, result);
            record = trainingRecordRepository.save(record);
        }

        return record;
    }

    public Map<String, Object> getAlgorithmTrainingStatus(String taskId) {
        try {
            String statusUrl = algorithmServiceUrl + "/api/v1/algo/train/status/" + taskId;
            ResponseEntity<String> response = statusRestTemplate.getForEntity(statusUrl, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return Map.of();
            }
            return objectMapper.readValue(response.getBody(), Map.class);
        } catch (Exception e) {
            log.debug("Unable to fetch algorithm training status for task {}: {}", taskId, e.getMessage());
            return Map.of();
        }
    }

    private void preventDuplicateDataset(Long projectId, Map<String, Object> metadata, boolean forceRetrain) {
        String fingerprint = metadata == null ? null : String.valueOf(metadata.getOrDefault("datasetFingerprint", ""));
        String legacySignature = legacySignatureFromMetadata(metadata);
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = null;
        }
        List<ModelTrainingRecord> completed = trainingRecordRepository.findByProjectIdAndStatus(
                projectId, ModelTrainingRecord.TrainingStatus.COMPLETED);
        for (ModelTrainingRecord previous : completed) {
            String previousFingerprint = extractDatasetFingerprint(previous);
            String previousLegacySignature = extractLegacyDatasetSignature(previous);
            boolean sameFingerprint = fingerprint != null && fingerprint.equals(previousFingerprint);
            boolean sameLegacySignature = legacySignature != null && legacySignature.equals(previousLegacySignature);
            if (sameFingerprint || sameLegacySignature) {
                if (forceRetrain) {
                    log.warn("Force retraining with unchanged dataset: projectId={}, previousTrainingRecordId={}",
                            projectId, previous.getId());
                    metadata.put("duplicateOfTrainingRecordId", previous.getId());
                    metadata.put("forceRetrain", true);
                    return;
                }
                throw new RuntimeException("本次训练数据与上一次完成训练完全一致，没有检测到新增回流数据；如确需复训，请勾选允许无新增数据复训。上次训练记录 #" + previous.getId());
            }
        }
    }

    private String legacySignatureFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object main = metadata.get("mainLsProjectId");
        Object reviewed = metadata.get("reviewedIncrementalProjects");
        Object train = metadata.get("trainImages");
        Object val = metadata.get("valImages");
        Object annotations = metadata.get("totalAnnotations");
        if (main == null || reviewed == null || train == null || val == null || annotations == null) {
            return null;
        }
        return "main=" + main + "|reviewed=" + reviewed + "|train=" + train + "|val=" + val + "|ann=" + annotations;
    }

    private String extractLegacyDatasetSignature(ModelTrainingRecord record) {
        if (record == null || record.getTestResults() == null || record.getTestResults().isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseStoredJson(record.getTestResults());
            if (root == null) {
                return null;
            }
            JsonNode metadata = root.path("metadata");
            if (metadata.isMissingNode()) {
                return null;
            }
            return "main=" + metadata.path("mainLsProjectId").asText()
                    + "|reviewed=" + objectMapper.convertValue(metadata.path("reviewedIncrementalProjects"), Object.class)
                    + "|train=" + root.path("trainImages").asInt()
                    + "|val=" + root.path("valImages").asInt()
                    + "|ann=" + root.path("totalAnnotations").asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDatasetFingerprint(ModelTrainingRecord record) {
        if (record == null || record.getTestResults() == null || record.getTestResults().isBlank()) {
            return null;
        }
        try {
            JsonNode root = parseStoredJson(record.getTestResults());
            if (root == null) {
                return null;
            }
            JsonNode metadata = root.path("metadata");
            JsonNode fingerprint = metadata.path("datasetFingerprint");
            return fingerprint.isMissingNode() || fingerprint.isNull() ? null : fingerprint.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseStoredJson(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(value);
        for (int i = 0; i < 8 && node != null && node.isTextual(); i++) {
            String text = node.asText();
            if (text == null || text.isBlank()) {
                return null;
            }
            node = objectMapper.readTree(text);
        }
        return node;
    }

    public ModelTrainingRecord refreshTrainingRecord(ModelTrainingRecord record) {
        Map<String, Object> status = getAlgorithmTrainingStatus(record.getTaskId());
        Object statusValue = status.get("status");
        if (statusValue != null) {
            String normalized = statusValue.toString().toUpperCase();
            if ("COMPLETED".equals(normalized) && record.getStatus() != ModelTrainingRecord.TrainingStatus.COMPLETED) {
                record.setStatus(ModelTrainingRecord.TrainingStatus.COMPLETED);
                record.setCompletedAt(LocalDateTime.now());
                record = trainingRecordRepository.save(record);
                try {
                    record = getTrainingResults(record.getTaskId());
                    record.setStatus(ModelTrainingRecord.TrainingStatus.COMPLETED);
                    if (record.getCompletedAt() == null) {
                        record.setCompletedAt(LocalDateTime.now());
                    }
                    record = trainingRecordRepository.save(record);
                    if (record.getRoundId() != null) {
                        roundService.markRoundTrainingCompleted(record.getRoundId(), record.getId());
                    }
                    return record;
                } catch (Exception e) {
                    log.warn("Failed to fetch training results for task {}: {}", record.getTaskId(), e.getMessage());
                }
            } else if ("FAILED".equals(normalized) && record.getStatus() != ModelTrainingRecord.TrainingStatus.FAILED) {
                record.setStatus(ModelTrainingRecord.TrainingStatus.FAILED);
                record.setCompletedAt(LocalDateTime.now());
                Object error = status.getOrDefault("error_message", status.get("error"));
                record.setErrorMessage(error != null ? error.toString() : "训练失败");
            } else if ("CANCELLED".equals(normalized) && record.getStatus() != ModelTrainingRecord.TrainingStatus.CANCELLED) {
                record.setStatus(ModelTrainingRecord.TrainingStatus.CANCELLED);
                record.setCompletedAt(LocalDateTime.now());
            }
            record = trainingRecordRepository.save(record);
        }

        if (record.getStatus() == ModelTrainingRecord.TrainingStatus.COMPLETED) {
            record = hydrateCompletedRecordFromOutput(record);
        }
        return record;
    }

    public ModelTrainingRecord hydrateCompletedRecordFromOutput(ModelTrainingRecord record) {
        if (record.getBestModelPath() != null && record.getMap50() != null) {
            return record;
        }
            Path outputDir = Paths.get(record.getOutputDir() != null ? record.getOutputDir() : trainingOutputBasePath + "/project_" + record.getProjectId());
        if (!Files.isDirectory(outputDir)) {
            return record;
        }
        try {
            Optional<Path> latestRun = findLatestRunDir(outputDir);
            if (latestRun.isEmpty()) {
                return record;
            }

            Path runDir = latestRun.get();
            Path best = runDir.resolve("weights").resolve("best.pt");
            Path last = runDir.resolve("weights").resolve("last.pt");
            Path resultsCsv = runDir.resolve("results.csv");
            Path logFile = outputDir.resolve(runDir.getFileName().toString() + "_training.log");

            if (Files.exists(best)) {
                record.setBestModelPath(best.toString());
            }
            if (Files.exists(last)) {
                record.setLastModelPath(last.toString());
            }
            if (Files.exists(logFile)) {
                record.setLogFilePath(logFile.toString());
            }
            applyMetricsFromResultsCsv(record, resultsCsv);
            return trainingRecordRepository.save(record);
        } catch (IOException e) {
            log.warn("Failed to hydrate training record {} from output dir: {}", record.getId(), e.getMessage());
            return record;
        }
    }

    public Map<String, Double> getFinalLossSummary(ModelTrainingRecord record) {
        Path outputDir = Paths.get(record.getOutputDir() != null ? record.getOutputDir() : trainingOutputBasePath + "/project_" + record.getProjectId());
        if (!Files.isDirectory(outputDir)) {
            return Map.of();
        }
        try {
            Optional<Path> latestRun = findLatestRunDir(outputDir);
            if (latestRun.isEmpty()) {
                return Map.of();
            }
            return parseLossSummary(latestRun.get().resolve("results.csv"));
        } catch (IOException e) {
            log.warn("Failed to read final loss summary for record {}: {}", record.getId(), e.getMessage());
            return Map.of();
        }
    }

    private void applyTrainingResult(ModelTrainingRecord record, JsonNode result) {
        if (result.has("best_model_path")) {
            record.setBestModelPath(result.get("best_model_path").asText());
        }
        if (result.has("last_model_path")) {
            record.setLastModelPath(result.get("last_model_path").asText());
        }
        if (result.has("log_file")) {
            record.setLogFilePath(result.get("log_file").asText());
        }
        if (result.has("results_csv")) {
            applyMetricsFromResultsCsv(record, Paths.get(result.get("results_csv").asText()));
        }
        if (result.has("metrics") && result.get("metrics").isObject()) {
            JsonNode metrics = result.get("metrics");
            setIfPresent(metrics, "metrics/mAP50(B)", record::setMap50);
            setIfPresent(metrics, "metrics/mAP50-95(B)", record::setMap50_95);
            setIfPresent(metrics, "metrics/precision(B)", record::setPrecision);
            setIfPresent(metrics, "metrics/recall(B)", record::setRecall);
        }
    }

    private interface DoubleSetter {
        void set(Double value);
    }

    private void setIfPresent(JsonNode node, String key, DoubleSetter setter) {
        if (node.has(key) && node.get(key).isNumber()) {
            setter.set(node.get(key).asDouble());
        }
    }

    private void applyMetricsFromResultsCsv(ModelTrainingRecord record, Path resultsCsv) {
        if (resultsCsv == null || !Files.exists(resultsCsv)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(resultsCsv);
            if (lines.size() < 2) {
                return;
            }
            String[] headers = splitCsvLine(lines.get(0));
            String[] values = splitCsvLine(lines.get(lines.size() - 1));
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.length && i < values.length; i++) {
                row.put(headers[i].trim(), values[i].trim());
            }
            record.setPrecision(parseDouble(row.get("metrics/precision(B)")));
            record.setRecall(parseDouble(row.get("metrics/recall(B)")));
            record.setMap50(parseDouble(row.get("metrics/mAP50(B)")));
            record.setMap50_95(parseDouble(row.get("metrics/mAP50-95(B)")));
        } catch (Exception e) {
            log.warn("Failed to parse metrics from {}: {}", resultsCsv, e.getMessage());
        }
    }

    private Optional<Path> findLatestRunDir(Path outputDir) throws IOException {
        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("run_"))
                    .max(Comparator.comparing(path -> path.toFile().lastModified()));
        }
    }

    private Map<String, Double> parseLossSummary(Path resultsCsv) {
        if (resultsCsv == null || !Files.exists(resultsCsv)) {
            return Map.of();
        }
        try {
            List<String> lines = Files.readAllLines(resultsCsv);
            if (lines.size() < 2) {
                return Map.of();
            }
            String[] headers = splitCsvLine(lines.get(0));
            String[] values = splitCsvLine(lines.get(lines.size() - 1));
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.length && i < values.length; i++) {
                row.put(headers[i].trim(), values[i].trim());
            }
            Double trainLoss = sumPresent(
                    parseDouble(row.get("train/box_loss")),
                    parseDouble(row.get("train/cls_loss")),
                    parseDouble(row.get("train/dfl_loss"))
            );
            Double valLoss = sumPresent(
                    parseDouble(row.get("val/box_loss")),
                    parseDouble(row.get("val/cls_loss")),
                    parseDouble(row.get("val/dfl_loss"))
            );
            Map<String, Double> summary = new HashMap<>();
            if (trainLoss != null) {
                summary.put("finalTrainLoss", trainLoss);
            }
            if (valLoss != null) {
                summary.put("finalValLoss", valLoss);
            }
            return summary;
        } catch (Exception e) {
            log.warn("Failed to parse loss summary from {}: {}", resultsCsv, e.getMessage());
            return Map.of();
        }
    }

    private Double sumPresent(Double... values) {
        double total = 0.0;
        boolean hasValue = false;
        for (Double value : values) {
            if (value != null) {
                total += value;
                hasValue = true;
            }
        }
        return hasValue ? total : null;
    }

    private String[] splitCsvLine(String line) {
        return line.split("\\s*,\\s*");
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
                    String status = statusJson.get("status").asText().toUpperCase();

                    if ("COMPLETED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.COMPLETED);
                        record.setCompletedAt(LocalDateTime.now());

                        getTrainingResults(record.getTaskId());
                        if (record.getRoundId() != null) {
                            roundService.markRoundTrainingCompleted(record.getRoundId(), record.getId());
                        }

                    } else if ("FAILED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.FAILED);
                        record.setCompletedAt(LocalDateTime.now());

                        if (statusJson.has("error_message") && !statusJson.get("error_message").isNull()) {
                            record.setErrorMessage(statusJson.get("error_message").asText());
                        } else if (statusJson.has("error")) {
                            record.setErrorMessage(statusJson.get("error").asText());
                        }

                    } else if ("CANCELLED".equals(status)) {
                        record.setStatus(ModelTrainingRecord.TrainingStatus.CANCELLED);
                        record.setCompletedAt(LocalDateTime.now());
                    }

                    trainingRecordRepository.save(record);
                }
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                // 算法服务重启后内存任务丢失，标记为失败
                log.warn("Task {} not found on algorithm service (likely restarted). Marking as FAILED.", record.getTaskId());
                record.setStatus(ModelTrainingRecord.TrainingStatus.FAILED);
                record.setErrorMessage("算法服务重启，训练任务丢失");
                record.setCompletedAt(LocalDateTime.now());
                trainingRecordRepository.save(record);
            } catch (Exception e) {
                log.error("Failed to update training status for task {}: {}", record.getTaskId(), e.getMessage());
            }
        }
    }

    public List<ModelTrainingRecord> getCompletedTrainingsOrderByMap50Desc() {
        return trainingRecordRepository.findCompletedOrderByMap50Desc();
    }
}
