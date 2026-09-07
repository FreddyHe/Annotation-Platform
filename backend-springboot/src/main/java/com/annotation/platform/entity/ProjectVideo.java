package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "project_videos", indexes = {
        @Index(name = "idx_project_videos_project_id", columnList = "project_id"),
        @Index(name = "idx_project_videos_source_video_id", columnList = "source_video_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_project_video_project"))
    private Project project;

    @Column(name = "source_video_id", nullable = false, length = 120)
    private String sourceVideoId;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "duration_sec")
    private Double durationSec;

    @Column(name = "fps")
    private Double fps;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "total_frames")
    private Long totalFrames;

    @Column(name = "selected_frames")
    private Integer selectedFrames;

    @Column(name = "manifest_path", length = 500)
    private String manifestPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "annotated_videos_json", columnDefinition = "json")
    private List<Map<String, Object>> annotatedVideosJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "json")
    private Map<String, Object> metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
