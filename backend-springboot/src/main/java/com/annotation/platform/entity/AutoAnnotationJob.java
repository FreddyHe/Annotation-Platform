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
import java.util.Map;

@Entity
@Table(name = "auto_annotation_jobs", indexes = {
        @Index(name = "idx_auto_jobs_project_id", columnList = "project_id"),
        @Index(name = "idx_auto_jobs_status", columnList = "status"),
        @Index(name = "idx_auto_jobs_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoAnnotationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_auto_job_project"))
    private Project project;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    @Builder.Default
    private AnnotationMode mode = AnnotationMode.DINO_VLM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 30)
    @Builder.Default
    private JobStage currentStage = JobStage.INIT;

    @Column(name = "process_range", length = 30)
    private String processRange;

    @Column(name = "score_threshold")
    private Double scoreThreshold;

    @Column(name = "box_threshold")
    private Double boxThreshold;

    @Column(name = "text_threshold")
    private Double textThreshold;

    @Column(name = "total_images")
    @Builder.Default
    private Integer totalImages = 0;

    @Column(name = "processed_images")
    @Builder.Default
    private Integer processedImages = 0;

    @Column(name = "kept_detections")
    @Builder.Default
    private Integer keptDetections = 0;

    @Column(name = "discarded_detections")
    @Builder.Default
    private Integer discardedDetections = 0;

    @Column(name = "dino_task_id")
    private String dinoTaskId;

    @Column(name = "vlm_task_id")
    private String vlmTaskId;

    @Column(name = "progress_percent")
    @Builder.Default
    private Double progressPercent = 0.0;

    @Column(name = "cancel_requested")
    @Builder.Default
    private Boolean cancelRequested = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", columnDefinition = "json")
    private Map<String, Object> paramsJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum AnnotationMode {
        DINO_VLM,
        DINO_THRESHOLD
    }

    public enum JobStatus {
        PENDING,
        RUNNING,
        CANCELLING,
        CANCELLED,
        COMPLETED,
        FAILED
    }

    public enum JobStage {
        INIT,
        DINO,
        VLM,
        THRESHOLD_FILTER,
        SYNC
    }
}
