package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "inference_data_points", indexes = {
        @Index(name = "idx_point_project_round", columnList = "project_id, round_id"),
        @Index(name = "idx_point_project_pool", columnList = "project_id, pool_type"),
        @Index(name = "idx_point_used", columnList = "used_in_round_id"),
        @Index(name = "idx_point_ls_task", columnList = "ls_task_id"),
        @Index(name = "idx_point_ls_sub_project", columnList = "ls_sub_project_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceDataPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "inference_bbox_json", columnDefinition = "TEXT")
    private String inferenceBboxJson;

    @Column(name = "avg_confidence")
    private Double avgConfidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "pool_type", nullable = false, length = 30)
    private PoolType poolType;

    @Enumerated(EnumType.STRING)
    @Column(name = "vlm_decision", length = 20)
    private VlmDecision vlmDecision;

    @Column(name = "vlm_reasoning", columnDefinition = "TEXT")
    private String vlmReasoning;

    @Column(name = "vlm_processed_at")
    private LocalDateTime vlmProcessedAt;

    @Column(name = "human_reviewed")
    @Builder.Default
    private Boolean humanReviewed = false;

    @Column(name = "ls_task_id")
    private Long lsTaskId;

    @Column(name = "ls_sub_project_id")
    private Long lsSubProjectId;

    @Column(name = "used_in_round_id")
    private Long usedInRoundId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum PoolType {
        HIGH,
        LOW_A_CANDIDATE,
        LOW_A,
        LOW_B,
        DISCARDED
    }

    public enum VlmDecision {
        KEEP,
        DISCARD,
        UNCERTAIN
    }
}
