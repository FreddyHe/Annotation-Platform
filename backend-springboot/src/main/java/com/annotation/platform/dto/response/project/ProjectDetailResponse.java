package com.annotation.platform.dto.response.project;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDetailResponse {

    private Long id;
    private String name;
    private OrganizationInfo organization;
    private UserInfo createdBy;
    private ProjectStatus status;
    private Integer totalImages;
    private Integer processedImages;
    private List<String> labels;
    private java.util.Map<String, String> labelDefinitions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @JsonProperty("labelStudioProjectId")
    private Long lsProjectId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizationInfo {
        private Long id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
    }

    public enum ProjectStatus {
        DRAFT,
        UPLOADING,
        DETECTING,
        CLEANING,
        SYNCING,
        COMPLETED,
        FAILED
    }
}
