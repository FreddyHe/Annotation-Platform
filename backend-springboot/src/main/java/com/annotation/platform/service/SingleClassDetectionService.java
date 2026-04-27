package com.annotation.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.annotation.platform.entity.SingleClassDetectionRecord;
import com.annotation.platform.repository.SingleClassDetectionRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SingleClassDetectionService {

    @Value("${app.algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private SingleClassDetectionRecordRepository recordRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> detectSingleClass(
            File imageFile,
            Integer classId,
            String modelPath,
            Double confidenceThreshold,
            Double iouThreshold
    ) throws Exception {
        log.info("Starting single class detection: class_id={}, model={}", classId, modelPath);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        
        Resource resource = new FileSystemResource(imageFile);
        body.add("image", resource);
        body.add("class_id", String.valueOf(classId));
        body.add("model_path", modelPath);
        body.add("confidence_threshold", String.valueOf(confidenceThreshold));
        body.add("iou_threshold", String.valueOf(iouThreshold));

        String detectionUrl = algorithmServiceUrl + "/api/v1/algo/single-class-detection";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(detectionUrl, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to perform single class detection: " + response.getStatusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", responseJson.get("success").asBoolean());
        result.put("message", responseJson.get("message").asText());
        
        if (responseJson.has("image_base64") && !responseJson.get("image_base64").isNull()) {
            result.put("image_base64", responseJson.get("image_base64").asText());
        }
        
        if (responseJson.has("detections") && !responseJson.get("detections").isNull()) {
            result.put("detections", objectMapper.convertValue(responseJson.get("detections"), Object.class));
        }

        return result;
    }

    public SingleClassDetectionRecord saveDetectionRecord(
            Long userId,
            String modelId,
            String modelName,
            String modelPath,
            Integer classId,
            String className,
            String imagePath,
            Double confidenceThreshold,
            Double iouThreshold,
            Map<String, Object> result
    ) {
        try {
            Object detectionsObj = result.get("detections");
            int count = 0;
            double averageConfidence = 0.0;
            if (detectionsObj instanceof List<?> detections) {
                count = detections.size();
                averageConfidence = detections.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> (Map<?, ?>) item)
                        .mapToDouble(item -> item.get("confidence") instanceof Number ? ((Number) item.get("confidence")).doubleValue() : 0.0)
                        .average()
                        .orElse(0.0);
            }

            SingleClassDetectionRecord record = SingleClassDetectionRecord.builder()
                    .userId(userId)
                    .modelId(modelId)
                    .modelName(modelName)
                    .modelPath(modelPath)
                    .classId(classId)
                    .className(className)
                    .imagePath(imagePath)
                    .detectionCount(count)
                    .averageConfidence(averageConfidence)
                    .confidenceThreshold(confidenceThreshold)
                    .iouThreshold(iouThreshold)
                    .resultJson(objectMapper.writeValueAsString(result))
                    .build();
            return recordRepository.save(record);
        } catch (Exception e) {
            log.warn("保存单类别检测历史失败: {}", e.getMessage());
            return null;
        }
    }

    public List<SingleClassDetectionRecord> listDetectionHistory(Long userId) {
        return recordRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void clearDetectionHistory(Long userId) {
        recordRepository.deleteByUserId(userId);
    }

    public Map<String, Object> getModelInfo() throws Exception {
        String infoUrl = algorithmServiceUrl + "/api/v1/algo/single-class-detection/model-info";

        ResponseEntity<String> response = restTemplate.getForEntity(infoUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get model info: " + response.getStatusCode());
        }

        return objectMapper.readValue(response.getBody(), Map.class);
    }
}
