package com.annotation.platform.dto;

import com.annotation.platform.entity.ProjectImage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectImageResponse {
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private LocalDateTime uploadedAt;
    private String status;

    public static ProjectImageResponse fromEntity(ProjectImage image) {
        return ProjectImageResponse.builder()
                .id(image.getId())
                .fileName(image.getFileName())
                .filePath(image.getFilePath())
                .fileSize(image.getFileSize())
                .uploadedAt(image.getUploadedAt())
                .status(image.getStatus().name())
                .build();
    }
}
