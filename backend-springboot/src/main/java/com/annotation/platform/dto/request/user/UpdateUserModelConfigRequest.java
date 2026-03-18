package com.annotation.platform.dto.request.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserModelConfigRequest {
    private String vlmApiKey;
    private String vlmBaseUrl;
    private String vlmModelName;

    private String llmApiKey;
    private String llmBaseUrl;
    private String llmModelName;
}

