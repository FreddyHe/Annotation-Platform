package com.annotation.platform.dto.response.user;

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
public class UserProfileResponse {

    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String avatarUrl;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    private Long orgId;
    private String orgName;
    private String orgDisplayName;
    private Boolean isOrgCreator;

    private Long totalProjects;
    private Long totalImages;
    private Long processingTasks;
    private Long completedTasks;

    private List<RecentProjectInfo> recentProjects;

    private Boolean isActive;
    private Boolean lsSyncStatus;
    private Long lsUserId;
    private String lsEmail;
    private String lsPassword;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentProjectInfo {
        private Long id;
        private String name;
        private String status;
        private Integer totalImages;
        private Integer processedImages;
        private LocalDateTime createdAt;
    }
}
