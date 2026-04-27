package com.annotation.platform.service.algorithm.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.dto.request.algorithm.DinoDetectRequest;
import com.annotation.platform.dto.request.algorithm.VlmCleanRequest;
import com.annotation.platform.dto.response.algorithm.DinoDetectResponse;
import com.annotation.platform.dto.response.algorithm.VlmCleanResponse;
import com.annotation.platform.service.algorithm.AlgorithmService;
import com.annotation.platform.service.user.UserModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmServiceImpl implements AlgorithmService {

    @Value("${algorithm.url:http://localhost:8001}")
    private String algorithmServiceUrl;

    @Value("${algorithm.timeout:600000}")
    private Integer timeout;

    @Value("${app.file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    private final UserModelConfigService userModelConfigService;
    private final RestTemplate restTemplate;

    @Override
    @SuppressWarnings("unchecked")
    public DinoDetectResponse startDinoDetection(DinoDetectRequest request) {
        log.info("Starting DINO detection: projectId={}, images={}", 
                request.getProjectId(), request.getImagePaths().size());
        
        try {
            String url = String.format("%s/api/v1/algo/dino/detect", algorithmServiceUrl);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("project_id", request.getProjectId());
            requestBody.put("image_paths", convertToAbsolutePaths(request.getImagePaths()));
            requestBody.put("labels", request.getLabels());
            requestBody.put("api_key", request.getApiKey());
            requestBody.put("endpoint", request.getEndpoint());
            requestBody.put("task_id", request.getTaskId());
            if (request.getBoxThreshold() != null) {
                requestBody.put("box_threshold", request.getBoxThreshold());
            }
            if (request.getTextThreshold() != null) {
                requestBody.put("text_threshold", request.getTextThreshold());
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject jsonResponse = JSON.parseObject(response.getBody());
                List<Map<String, Object>> resultsList = new ArrayList<>();
                if (jsonResponse.getJSONArray("results") != null) {
                    resultsList = (List<Map<String, Object>>) (List<?>) jsonResponse.getJSONArray("results").toJavaList(Map.class);
                }
                return DinoDetectResponse.builder()
                        .success(jsonResponse.getBoolean("success"))
                        .message(jsonResponse.getString("message"))
                        .taskId(jsonResponse.getString("task_id"))
                        .status(jsonResponse.getString("status"))
                        .results(resultsList)
                        .build();
            }
            
            log.error("DINO detection failed: status={}, body={}", 
                    response.getStatusCode(), response.getBody());
            return DinoDetectResponse.builder()
                    .success(false)
                    .message("Failed to start DINO detection")
                    .build();
                    
        } catch (Exception e) {
            log.error("DINO detection error: {}", e.getMessage(), e);
            return DinoDetectResponse.builder()
                    .success(false)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }

    private List<String> convertToAbsolutePaths(List<String> relativePaths) {
        if (relativePaths == null) {
            return new ArrayList<>();
        }
        return relativePaths.stream()
                .map(path -> {
                    if (path.startsWith("/")) {
                        return path;
                    }
                    return uploadBasePath + "/" + path;
                })
                .collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public VlmCleanResponse startVlmCleaning(VlmCleanRequest request) {
        log.info("Starting VLM cleaning: projectId={}, detections={}", 
                request.getProjectId(), request.getDetections().size());
        
        try {
            String url = String.format("%s/api/v1/algo/clean/vlm", algorithmServiceUrl);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("project_id", request.getProjectId());
            requestBody.put("detections", request.getDetections());
            requestBody.put("label_definitions", request.getLabelDefinitions());
            requestBody.put("task_id", request.getTaskId());

            var effectiveConfig = userModelConfigService.getEffectiveConfig(request.getUserId());
            requestBody.put("vlm_api_key", effectiveConfig.getVlmApiKey());
            requestBody.put("vlm_base_url", effectiveConfig.getVlmBaseUrl());
            requestBody.put("vlm_model_name", effectiveConfig.getVlmModelName());
            requestBody.put("api_key", effectiveConfig.getVlmApiKey());
            requestBody.put("endpoint", effectiveConfig.getVlmBaseUrl());
            
            // 提取 image_paths 列表（从 detections 中提取唯一的 image_path）
            if (request.getImagePaths() != null && !request.getImagePaths().isEmpty()) {
                requestBody.put("image_paths", request.getImagePaths());
            } else {
                // 从 detections 中提取 image_path
                List<String> imagePaths = request.getDetections().stream()
                        .map(d -> (String) d.get("image_path"))
                        .filter(path -> path != null)
                        .distinct()
                        .collect(Collectors.toList());
                requestBody.put("image_paths", imagePaths);
            }
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject jsonResponse = JSON.parseObject(response.getBody());
                List<Map<String, Object>> resultsList = new ArrayList<>();
                if (jsonResponse.getJSONArray("results") != null) {
                    resultsList = (List<Map<String, Object>>) (List<?>) jsonResponse.getJSONArray("results").toJavaList(Map.class);
                }
                return VlmCleanResponse.builder()
                        .success(jsonResponse.getBoolean("success"))
                        .message(jsonResponse.getString("message"))
                        .taskId(jsonResponse.getString("task_id"))
                        .status(jsonResponse.getString("status"))
                        .results(resultsList)
                        .build();
            }
            
            log.error("VLM cleaning failed: status={}, body={}", 
                    response.getStatusCode(), response.getBody());
            return VlmCleanResponse.builder()
                    .success(false)
                    .message("Failed to start VLM cleaning")
                    .build();
                    
        } catch (Exception e) {
            log.error("VLM cleaning error: {}", e.getMessage(), e);
            return VlmCleanResponse.builder()
                    .success(false)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public Object getTaskStatus(String taskId) {
        String url = String.format("%s/api/v1/algo/status/%s", algorithmServiceUrl, taskId);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK) {
                return JSON.parseObject(response.getBody());
            }
            
            log.error("Task status request failed: URL={}, statusCode={}, body={}", 
                    url, response.getStatusCode(), response.getBody());
            throw new RuntimeException("获取任务状态失败: " + response.getBody());
            
        } catch (HttpClientErrorException e) {
            log.error("轮询请求失败! URL: {}, 状态码: {}, Error: {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("API请求失败: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Get task status error: {}", e.getMessage(), e);
            throw new RuntimeException("获取任务状态失败: " + e.getMessage());
        }
    }

    @Override
    public Object getTaskResults(String taskId) {
        String url = String.format("%s/api/v1/algo/results/%s", algorithmServiceUrl, taskId);
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode() == HttpStatus.OK) {
                return JSON.parseObject(response.getBody());
            }
            
            log.error("Task results request failed: URL={}, statusCode={}, body={}", 
                    url, response.getStatusCode(), response.getBody());
            throw new RuntimeException("获取任务结果失败: " + response.getBody());
            
        } catch (HttpClientErrorException e) {
            log.error("轮询请求失败! URL: {}, 状态码: {}, Error: {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("API请求失败: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Get task results error: {}", e.getMessage(), e);
            throw new RuntimeException("获取任务结果失败: " + e.getMessage());
        }
    }
}
