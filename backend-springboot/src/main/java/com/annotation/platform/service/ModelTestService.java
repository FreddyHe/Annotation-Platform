package com.annotation.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ModelTestService {

    @Value("${algorithm.service.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String startTest(
            String modelPath,
            List<String> imagePaths,
            Double confThreshold,
            Double iouThreshold,
            String device
    ) throws Exception {
        log.info("Starting model test with model: {}", modelPath);

        Map<String, Object> testRequest = new HashMap<>();
        testRequest.put("model_path", modelPath);
        testRequest.put("image_paths", imagePaths);
        testRequest.put("conf_threshold", confThreshold);
        testRequest.put("iou_threshold", iouThreshold);
        testRequest.put("device", device);

        String testUrl = algorithmServiceUrl + "/api/v1/algo/test/yolo";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(testRequest, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(testUrl, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start test: " + response.getStatusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        return responseJson.get("task_id").asText();
    }

    public String startTestWithUpload(
            String modelPath,
            List<File> imageFiles,
            Double confThreshold,
            Double iouThreshold,
            String device
    ) throws Exception {
        log.info("Starting model test with upload, model: {}, images: {}", modelPath, imageFiles.size());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        
        for (File file : imageFiles) {
            Resource resource = new FileSystemResource(file);
            body.add("files", resource);
        }
        
        body.add("model_path", modelPath);
        body.add("conf_threshold", String.valueOf(confThreshold));
        body.add("iou_threshold", String.valueOf(iouThreshold));
        body.add("device", device);

        String testUrl = algorithmServiceUrl + "/api/v1/algo/test/yolo/upload";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(testUrl, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start test with upload: " + response.getStatusCode());
        }

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        return responseJson.get("task_id").asText();
    }

    public Map<String, Object> getTestStatus(String taskId) throws Exception {
        String statusUrl = algorithmServiceUrl + "/api/v1/algo/test/status/" + taskId;

        ResponseEntity<String> response = restTemplate.getForEntity(statusUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get test status: " + response.getStatusCode());
        }

        return objectMapper.readValue(response.getBody(), Map.class);
    }

    public Map<String, Object> getTestResults(String taskId) throws Exception {
        String resultsUrl = algorithmServiceUrl + "/api/v1/algo/test/results/" + taskId;

        ResponseEntity<String> response = restTemplate.getForEntity(resultsUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get test results: " + response.getStatusCode());
        }

        return objectMapper.readValue(response.getBody(), Map.class);
    }

    public Map<String, Object> getTestResultForImage(String taskId, int imageIndex) throws Exception {
        String resultUrl = algorithmServiceUrl + "/api/v1/algo/test/results/" + taskId + "/image/" + imageIndex;

        ResponseEntity<String> response = restTemplate.getForEntity(resultUrl, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to get test result for image: " + response.getStatusCode());
        }

        return objectMapper.readValue(response.getBody(), Map.class);
    }

    public void cancelTest(String taskId) throws Exception {
        String cancelUrl = algorithmServiceUrl + "/api/v1/algo/test/cancel/" + taskId;
        restTemplate.postForEntity(cancelUrl, null, String.class);
    }
}
