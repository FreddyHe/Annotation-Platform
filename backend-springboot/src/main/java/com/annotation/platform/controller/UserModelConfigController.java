package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.user.UpdateUserModelConfigRequest;
import com.annotation.platform.dto.response.user.UserModelConfigResponse;
import com.annotation.platform.service.user.UserModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/user/model-config")
@RequiredArgsConstructor
public class UserModelConfigController {

    private final UserModelConfigService userModelConfigService;
    private final RestTemplate restTemplate;

    @Value("${app.algorithm.url:${algorithm.url:http://localhost:8001}}")
    private String algorithmServiceUrl;

    @GetMapping
    public Result<UserModelConfigResponse> getCurrentUserModelConfig(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        UserModelConfigResponse response = userModelConfigService.getCurrentUserConfig(userId);
        return Result.success(response);
    }

    @PutMapping
    public Result<UserModelConfigResponse> upsertCurrentUserModelConfig(
            @RequestBody UpdateUserModelConfigRequest body,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        UserModelConfigResponse response = userModelConfigService.upsertCurrentUserConfig(userId, body);
        return Result.success(response);
    }

    @PostMapping("/test-vlm")
    public Result<Map<String, Object>> testVlmConnectivity(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        var effective = userModelConfigService.getEffectiveConfig(userId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("api_key", effective.getVlmApiKey());
        payload.put("base_url", effective.getVlmBaseUrl());
        payload.put("model_name", effective.getVlmModelName());

        boolean success = callAlgorithmServiceTest("/api/v1/model-config/test-vlm", payload);
        return Result.success(Map.of("success", success));
    }

    @PostMapping("/test-llm")
    public Result<Map<String, Object>> testLlmConnectivity(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        var effective = userModelConfigService.getEffectiveConfig(userId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("api_key", effective.getLlmApiKey());
        payload.put("base_url", effective.getLlmBaseUrl());
        payload.put("model_name", effective.getLlmModelName());

        boolean success = callAlgorithmServiceTest("/api/v1/model-config/test-llm", payload);
        return Result.success(Map.of("success", success));
    }

    @SuppressWarnings("unchecked")
    private boolean callAlgorithmServiceTest(String path, Map<String, Object> payload) {
        String url = algorithmServiceUrl + path;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            Object success = response.getBody() != null ? response.getBody().get("success") : null;
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.error("Model config test failed: url={}, error={}", url, e.getMessage());
            return false;
        }
    }
}

