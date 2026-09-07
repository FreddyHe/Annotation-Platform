package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "auto_label_job", indexes = {
        @Index(name = "idx_auto_label_job_project_id", columnList = "project_id"),
        @Index(name = "idx_auto_label_job_route_plan_id", columnList = "route_plan_id"),
        @Index(name = "idx_auto_label_job_status", columnList = "status"),
        @Index(name = "idx_auto_label_job_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoLabelJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_auto_label_job_project"))
    private Project project;

    @Column(name = "dataset_id", nullable = false, length = 120)
    private String datasetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_plan_id", foreignKey = @ForeignKey(name = "fk_auto_label_job_route_plan"))
    private ModelRoutePlan routePlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.CREATED;

    @Column(name = "pipeline", nullable = false, length = 80)
    @Builder.Default
    private String pipeline = "AUTO_LABEL_TO_MODEL";

    @Column(name = "priority_mode", nullable = false, length = 50)
    @Builder.Default
    private String priorityMode = "quality_first";

    @Column(name = "total_images")
    @Builder.Default
    private Integer totalImages = 0;

    @Column(name = "processed_images")
    @Builder.Default
    private Integer processedImages = 0;

    @Column(name = "failed_images")
    @Builder.Default
    private Integer failedImages = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "runtime_json", columnDefinition = "json")
    private Map<String, Object> runtimeJson;

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

    public enum Status {
        CREATED,
        PROBING,
        RUNNING,
        SYNCING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
