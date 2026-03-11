package com.annotation.platform.dto.response.algorithm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmCleanResult {
    private String decision;
    private String reasoning;
}
