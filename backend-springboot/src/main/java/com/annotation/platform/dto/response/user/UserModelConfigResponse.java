package com.annotation.platform.dto.response.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModelConfigResponse {
    private Long id;
    private Long userId;

    private String vlmApiKey;
    private String vlmBaseUrl;
    private String vlmModelName;

    private String llmApiKey;
    private String llmBaseUrl;
    private String llmModelName;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

