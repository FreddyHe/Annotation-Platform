package com.annotation.platform.service;

import com.annotation.platform.dto.CreateTrainingTaskRequest;
import com.annotation.platform.dto.CustomModelResponse;
import com.annotation.platform.entity.CustomModel;
import com.annotation.platform.entity.CustomModelClass;
import com.annotation.platform.repository.CustomModelRepository;
import com.annotation.platform.repository.CustomModelClassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomModelService {

    private final CustomModelRepository modelRepo;
    private final CustomModelClassRepository classRepo;
    private final RestTemplate restTemplate;

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${app.callback.url:}")
    private String callbackUrl;

    @Transactional
    public CustomModel createTrainingTask(Long userId, CreateTrainingTaskRequest req) {
        validateDatasetConfig(req);
        CustomModel model = CustomModel.builder()
                .userId(userId)
                .modelName(resolveModelName(req))
                .projectId(null)
                .targetClassName(blankToNull(req.getTargetClassName()))
                .datasetSource(req.getDatasetSource() != null ? req.getDatasetSource() : "ROBOFLOW")
                .datasetUri(req.getDatasetUri())
                .downloadCommand(req.getDownloadCommand())
                .epochs(req.getEpochs() != null ? req.getEpochs() : 50)
                .batchSize(req.getBatchSize() != null ? req.getBatchSize() : 16)
                .imageSize(req.getImageSize() != null ? req.getImageSize() : 640)
                .learningRate(req.getLearningRate() != null ? req.getLearningRate() : 0.01)
                .usePretrained(req.getUsePretrained() == null || req.getUsePretrained())
                .status("PENDING")
                .statusMessage("AutoML 任务已创建，等待提交到算法服务")
                .progress(0.0)
                .trainingLog("AutoML 任务已创建，等待提交到算法服务")
                .build();
        model = modelRepo.save(model);
        submitToAlgorithmService(model);
        return model;
    }

    @Async
    public void submitToAlgorithmService(CustomModel model) {
        try {
            model.setStatus("DOWNLOADING");
            model.setStatusMessage("已提交到算法服务");
            model.setProgress(0.05);
            appendLog(model, "已提交到算法服务");
            modelRepo.save(model);

            String url = algorithmServiceUrl + "/api/v1/training/start";
            Map<String, Object> body = new HashMap<>();
            body.put("task_id", String.valueOf(model.getId()));
            body.put("model_name", model.getModelName());
            body.put("dataset_source", model.getDatasetSource());
            body.put("dataset_uri", model.getDatasetUri());
            body.put("download_command", model.getDownloadCommand());
            body.put("epochs", model.getEpochs());
            body.put("batch_size", model.getBatchSize() != null ? model.getBatchSize() : 16);
            body.put("image_size", model.getImageSize() != null ? model.getImageSize() : 640);
            body.put("learning_rate", model.getLearningRate() != null ? model.getLearningRate() : 0.01);
            body.put("use_pretrained", model.getUsePretrained() == null || model.getUsePretrained());
            body.put("automl", true);
            if (callbackUrl != null && !callbackUrl.isBlank()) {
                body.put("callback_url", callbackUrl);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(url, entity, Map.class);
            log.info("训练任务已提交到算法服务: taskId={}", model.getId());
        } catch (Exception e) {
            log.warn("提交到算法服务失败（算法服务可能未启动）: {}", e.getMessage());
            model.setStatus("PENDING");
            model.setStatusMessage("提交到算法服务失败: " + e.getMessage() + "（算法服务启动后可重试）");
            model.setProgress(0.0);
            appendLog(model, "提交到算法服务失败: " + e.getMessage());
            modelRepo.save(model);
        }
    }

    @Transactional
    public void handleCallback(Map<String, Object> data) {
        String taskId = String.valueOf(data.get("taskId"));
        String status = (String) data.get("status");

        CustomModel model = modelRepo.findById(Long.valueOf(taskId))
                .orElseThrow(() -> new RuntimeException("模型不存在: " + taskId));

        if ("COMPLETED".equals(status)) {
            model.setStatus("COMPLETED");
            model.setModelPath((String) data.get("modelPath"));
            model.setStatusMessage("训练完成");
            model.setProgress(1.0);
            model.setCompletedAt(LocalDateTime.now());
            if (data.get("mapScore") != null) {
                model.setMapScore(((Number) data.get("mapScore")).doubleValue());
            }
            if (data.get("precisionScore") != null) {
                model.setPrecisionScore(((Number) data.get("precisionScore")).doubleValue());
            }
            if (data.get("recallScore") != null) {
                model.setRecallScore(((Number) data.get("recallScore")).doubleValue());
            }
            appendLog(model, "训练完成");
            modelRepo.save(model);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) data.get("classes");
            if (classes != null) {
                classRepo.deleteByModelId(model.getId());
                for (Map<String, Object> cls : classes) {
                    classRepo.save(CustomModelClass.builder()
                            .modelId(model.getId())
                            .classId(((Number) cls.get("class_id")).intValue())
                            .className((String) cls.get("name"))
                            .cnName((String) cls.getOrDefault("cn_name", cls.get("name")))
                            .build());
                }
            }
        } else if ("FAILED".equals(status)) {
            model.setStatus("FAILED");
            model.setStatusMessage((String) data.getOrDefault("message", "训练失败"));
            appendLog(model, model.getStatusMessage());
            modelRepo.save(model);
        }
    }

    @Transactional
    public void syncTrainingStatus(Long modelId) {
        CustomModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new RuntimeException("模型不存在"));
        if ("COMPLETED".equals(model.getStatus()) || "FAILED".equals(model.getStatus())) {
            return;
        }
        try {
            String url = algorithmServiceUrl + "/api/v1/training/status/" + model.getId();
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> respData = (Map<String, Object>) resp.getBody().get("data");
                if (respData != null) {
                    model.setStatus((String) respData.get("status"));
                    model.setStatusMessage((String) respData.get("message"));
                    if (respData.get("progress") instanceof Number) {
                        model.setProgress(((Number) respData.get("progress")).doubleValue());
                    }
                    if (respData.get("logs") instanceof List<?> logs) {
                        model.setTrainingLog(logs.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\n")));
                    } else if (respData.get("message") != null) {
                        appendLog(model, String.valueOf(respData.get("message")));
                    }
                    if ("COMPLETED".equals(respData.get("status"))) {
                        model.setModelPath((String) respData.get("model_path"));
                        model.setProgress(1.0);
                        model.setCompletedAt(LocalDateTime.now());
                        if (respData.get("map_score") != null) {
                            model.setMapScore(((Number) respData.get("map_score")).doubleValue());
                        }
                        if (respData.get("precision_score") != null) {
                            model.setPrecisionScore(((Number) respData.get("precision_score")).doubleValue());
                        }
                        if (respData.get("recall_score") != null) {
                            model.setRecallScore(((Number) respData.get("recall_score")).doubleValue());
                        }
                        modelRepo.save(model);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> classes = (List<Map<String, Object>>) respData.get("classes");
                        if (classes != null) {
                            classRepo.deleteByModelId(model.getId());
                            for (Map<String, Object> cls : classes) {
                                classRepo.save(CustomModelClass.builder()
                                        .modelId(model.getId())
                                        .classId(((Number) cls.get("class_id")).intValue())
                                        .className((String) cls.get("name"))
                                        .cnName((String) cls.getOrDefault("cn_name", cls.get("name")))
                                        .build());
                            }
                        }
                    } else {
                        modelRepo.save(model);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("同步算法服务状态失败（可能未启动）: {}", e.getMessage());
        }
    }

    @Transactional
    public CustomModel retryTrainingTask(Long userId, Long modelId) {
        CustomModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new RuntimeException("模型不存在"));
        if (!model.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作该模型");
        }
        if ("DOWNLOADING".equals(model.getStatus()) || "CONVERTING".equals(model.getStatus()) || "TRAINING".equals(model.getStatus())) {
            throw new RuntimeException("训练任务正在进行中，不能重复提交");
        }
        model.setStatus("PENDING");
        model.setStatusMessage("任务已重新提交");
        model.setProgress(0.0);
        model.setCompletedAt(null);
        model.setModelPath(null);
        model.setMapScore(null);
        model.setPrecisionScore(null);
        model.setRecallScore(null);
        model.setTrainingLog("任务已重新提交");
        modelRepo.save(model);
        classRepo.deleteByModelId(model.getId());
        submitToAlgorithmService(model);
        return model;
    }

    @Transactional
    public void deleteModel(Long userId, Long modelId) {
        CustomModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new RuntimeException("模型不存在"));
        if (!model.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除该模型");
        }
        classRepo.deleteByModelId(modelId);
        modelRepo.delete(model);
    }

    public String getTrainingLog(Long modelId) {
        CustomModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new RuntimeException("模型不存在"));
        return model.getTrainingLog();
    }

    public Map<String, Object> inspectDataset(CreateTrainingTaskRequest req) {
        validateDatasetConfig(req);

        String url = algorithmServiceUrl + "/api/v1/training/inspect-dataset";
        Map<String, Object> body = new HashMap<>();
        body.put("dataset_source", req.getDatasetSource() != null ? req.getDatasetSource() : "ROBOFLOW");
        body.put("dataset_uri", req.getDatasetUri());
        body.put("download_command", req.getDownloadCommand());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("算法服务预检失败: " + response.getStatusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        return responseBody;
    }

    public List<CustomModelResponse> getAvailableModels(Long userId) {
        return modelRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "COMPLETED")
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    public List<CustomModelResponse> getUserModels(Long userId) {
        return modelRepo.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    public CustomModelResponse getModelDetail(Long modelId) {
        CustomModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new RuntimeException("模型不存在"));
        return toResponse(model);
    }

    private CustomModelResponse toResponse(CustomModel model) {
        List<CustomModelClass> classes = classRepo.findByModelIdOrderByClassIdAsc(model.getId());
        return CustomModelResponse.builder()
                .id(model.getId())
                .modelName(model.getModelName())
                .projectId(model.getProjectId())
                .targetClassName(model.getTargetClassName())
                .datasetSource(model.getDatasetSource())
                .datasetUri(model.getDatasetUri())
                .status(model.getStatus())
                .statusMessage(model.getStatusMessage())
                .epochs(model.getEpochs())
                .batchSize(model.getBatchSize())
                .imageSize(model.getImageSize())
                .learningRate(model.getLearningRate())
                .usePretrained(model.getUsePretrained())
                .progress(model.getProgress())
                .mapScore(model.getMapScore())
                .precisionScore(model.getPrecisionScore())
                .recallScore(model.getRecallScore())
                .modelPath(model.getModelPath())
                .datasetFormat(model.getDatasetFormat())
                .trainingLog(model.getTrainingLog())
                .createdAt(model.getCreatedAt())
                .completedAt(model.getCompletedAt())
                .classes(classes.stream().map(c -> CustomModelResponse.ModelClassInfo.builder()
                        .classId(c.getClassId())
                        .className(c.getClassName())
                        .cnName(c.getCnName())
                        .build()
                ).collect(java.util.stream.Collectors.toList()))
                .build();
    }

    private void appendLog(CustomModel model, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String existing = model.getTrainingLog();
        if (existing != null && existing.endsWith(message)) {
            return;
        }
        String timestamp = java.time.LocalDateTime.now().toString();
        String line = "[" + timestamp + "] " + message;
        model.setTrainingLog(existing == null || existing.isBlank() ? line : existing + "\n" + line);
    }

    private void validateDatasetConfig(CreateTrainingTaskRequest req) {
        String source = req.getDatasetSource() == null ? "ROBOFLOW" : req.getDatasetSource().toUpperCase();
        if ("ROBOFLOW".equals(source)) {
            if (req.getDownloadCommand() == null || req.getDownloadCommand().isBlank()) {
                throw new IllegalArgumentException("Roboflow 数据源需要填写下载命令");
            }
            return;
        }
        if (("URL_ZIP".equals(source) || "UPLOAD_ZIP".equals(source))
                && (req.getDatasetUri() == null || req.getDatasetUri().isBlank())) {
            throw new IllegalArgumentException("当前数据源需要填写或上传数据集地址");
        }
        if (!"URL_ZIP".equals(source) && !"UPLOAD_ZIP".equals(source)) {
            throw new IllegalArgumentException("不支持的数据源: " + source);
        }
    }

    private String resolveModelName(CreateTrainingTaskRequest req) {
        if (req.getModelName() != null && !req.getModelName().isBlank()) {
            return req.getModelName().trim();
        }
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMddHHmm"));
        return "AutoML检测模型-" + timestamp;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
