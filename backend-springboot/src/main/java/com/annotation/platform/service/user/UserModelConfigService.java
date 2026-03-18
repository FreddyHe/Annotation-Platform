package com.annotation.platform.service.user;

import com.annotation.platform.dto.request.user.UpdateUserModelConfigRequest;
import com.annotation.platform.dto.response.user.UserModelConfigResponse;
import com.annotation.platform.entity.UserModelConfig;
import com.annotation.platform.repository.UserModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserModelConfigService {

    public static final String DEFAULT_VLM_API_KEY = "sk-644be34708ab44a38a0a28c82e37d6b6";
    public static final String DEFAULT_VLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String DEFAULT_VLM_MODEL_NAME = "qwen-vl-plus";

    public static final String DEFAULT_LLM_API_KEY = "sk-AomDFLTBpbXd6JXk2hSv2WvzWccvww3TGkPRnA5L51ENOmNt";
    public static final String DEFAULT_LLM_BASE_URL = "https://api.chatanywhere.tech/v1";
    public static final String DEFAULT_LLM_MODEL_NAME = "gpt-4.1";

    private final UserModelConfigRepository userModelConfigRepository;

    @Transactional(readOnly = true)
    public UserModelConfigResponse getCurrentUserConfig(Long userId) {
        UserModelConfig effective = getEffectiveConfig(userId);
        return toResponse(effective, true);
    }

    @Transactional
    public UserModelConfigResponse upsertCurrentUserConfig(Long userId, UpdateUserModelConfigRequest request) {
        Optional<UserModelConfig> existingOpt = userModelConfigRepository.findByUserId(userId);

        UserModelConfig config = existingOpt.orElseGet(() -> UserModelConfig.builder()
                .userId(userId)
                .build());

        if (request.getVlmApiKey() != null && !request.getVlmApiKey().isBlank() && shouldUpdateSecret(request.getVlmApiKey())) {
            config.setVlmApiKey(request.getVlmApiKey().trim());
        }
        if (request.getVlmBaseUrl() != null && !request.getVlmBaseUrl().isBlank()) {
            config.setVlmBaseUrl(request.getVlmBaseUrl().trim());
        }
        if (request.getVlmModelName() != null && !request.getVlmModelName().isBlank()) {
            config.setVlmModelName(request.getVlmModelName().trim());
        }

        if (request.getLlmApiKey() != null && !request.getLlmApiKey().isBlank() && shouldUpdateSecret(request.getLlmApiKey())) {
            config.setLlmApiKey(request.getLlmApiKey().trim());
        }
        if (request.getLlmBaseUrl() != null && !request.getLlmBaseUrl().isBlank()) {
            config.setLlmBaseUrl(request.getLlmBaseUrl().trim());
        }
        if (request.getLlmModelName() != null && !request.getLlmModelName().isBlank()) {
            config.setLlmModelName(request.getLlmModelName().trim());
        }

        UserModelConfig saved = userModelConfigRepository.save(config);
        UserModelConfig effective = fillDefaults(saved);
        return toResponse(effective, true);
    }

    @Transactional(readOnly = true)
    public UserModelConfig getEffectiveConfig(Long userId) {
        if (userId == null) {
            return fillDefaults(UserModelConfig.builder().userId(null).build());
        }
        UserModelConfig config = userModelConfigRepository.findByUserId(userId)
                .orElseGet(() -> UserModelConfig.builder().userId(userId).build());
        return fillDefaults(config);
    }

    private UserModelConfig fillDefaults(UserModelConfig config) {
        if (config.getVlmApiKey() == null || config.getVlmApiKey().isBlank()) {
            config.setVlmApiKey(DEFAULT_VLM_API_KEY);
        }
        if (config.getVlmBaseUrl() == null || config.getVlmBaseUrl().isBlank()) {
            config.setVlmBaseUrl(DEFAULT_VLM_BASE_URL);
        }
        if (config.getVlmModelName() == null || config.getVlmModelName().isBlank()) {
            config.setVlmModelName(DEFAULT_VLM_MODEL_NAME);
        }
        if (config.getLlmApiKey() == null || config.getLlmApiKey().isBlank()) {
            config.setLlmApiKey(DEFAULT_LLM_API_KEY);
        }
        if (config.getLlmBaseUrl() == null || config.getLlmBaseUrl().isBlank()) {
            config.setLlmBaseUrl(DEFAULT_LLM_BASE_URL);
        }
        if (config.getLlmModelName() == null || config.getLlmModelName().isBlank()) {
            config.setLlmModelName(DEFAULT_LLM_MODEL_NAME);
        }
        return config;
    }

    private UserModelConfigResponse toResponse(UserModelConfig effective, boolean maskSecrets) {
        return UserModelConfigResponse.builder()
                .id(effective.getId())
                .userId(effective.getUserId())
                .vlmApiKey(maskSecrets ? maskApiKey(effective.getVlmApiKey()) : effective.getVlmApiKey())
                .vlmBaseUrl(effective.getVlmBaseUrl())
                .vlmModelName(effective.getVlmModelName())
                .llmApiKey(maskSecrets ? maskApiKey(effective.getLlmApiKey()) : effective.getLlmApiKey())
                .llmBaseUrl(effective.getLlmBaseUrl())
                .llmModelName(effective.getLlmModelName())
                .createdAt(effective.getCreatedAt())
                .updatedAt(effective.getUpdatedAt())
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        String prefix = trimmed.substring(0, 4);
        String suffix = trimmed.substring(trimmed.length() - 4);
        return prefix + "****" + suffix;
    }

    private boolean shouldUpdateSecret(String candidate) {
        return !candidate.contains("*");
    }
}
