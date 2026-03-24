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

    @Transactional
    public CustomModel createTrainingTask(Long userId, CreateTrainingTaskRequest req) {
        CustomModel model = CustomModel.builder()
                .userId(userId)
                .modelName(req.getModelName())
                .downloadCommand(req.getDownloadCommand())
                .epochs(req.getEpochs())
                .batchSize(req.getBatchSize() != null ? req.getBatchSize() : 16)
                .status("PENDING")
                .statusMessage("任务已创建，等待提交到算法服务")
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
            modelRepo.save(model);

            String url = algorithmServiceUrl + "/api/v1/training/start";
            Map<String, Object> body = new HashMap<>();
            body.put("task_id", String.valueOf(model.getId()));
            body.put("model_name", model.getModelName());
            body.put("download_command", model.getDownloadCommand());
            body.put("epochs", model.getEpochs());
            body.put("batch_size", model.getBatchSize() != null ? model.getBatchSize() : 16);
            body.put("callback_url", "http://localhost:8080/api/v1/custom-models/callback");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(url, entity, Map.class);
            log.info("训练任务已提交到算法服务: taskId={}", model.getId());
        } catch (Exception e) {
            log.warn("提交到算法服务失败（算法服务可能未启动）: {}", e.getMessage());
            model.setStatus("PENDING");
            model.setStatusMessage("提交到算法服务失败: " + e.getMessage() + "（算法服务启动后可重试）");
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
            model.setCompletedAt(LocalDateTime.now());
            if (data.get("mapScore") != null) {
                model.setMapScore(((Number) data.get("mapScore")).doubleValue());
            }
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
            modelRepo.save(model);
        }
    }

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
                    if ("COMPLETED".equals(respData.get("status"))) {
                        model.setModelPath((String) respData.get("model_path"));
                        model.setCompletedAt(LocalDateTime.now());
                        if (respData.get("map_score") != null) {
                            model.setMapScore(((Number) respData.get("map_score")).doubleValue());
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
                .status(model.getStatus())
                .statusMessage(model.getStatusMessage())
                .epochs(model.getEpochs())
                .mapScore(model.getMapScore())
                .modelPath(model.getModelPath())
                .datasetFormat(model.getDatasetFormat())
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
}
