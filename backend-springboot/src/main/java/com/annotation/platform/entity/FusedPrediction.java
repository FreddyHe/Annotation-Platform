package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "fused_prediction", indexes = {
        @Index(name = "idx_fused_prediction_job_id", columnList = "job_id"),
        @Index(name = "idx_fused_prediction_image_id", columnList = "image_id"),
        @Index(name = "idx_fused_prediction_label", columnList = "label"),
        @Index(name = "idx_fused_prediction_synced", columnList = "synced_to_label_studio")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FusedPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fused_prediction_job"))
    private AutoLabelJob job;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox_json", columnDefinition = "json")
    private List<Double> bboxJson;

    @Column(name = "final_score")
    private Double finalScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_models_json", columnDefinition = "json")
    private List<String> sourceModelsJson;

    @Column(name = "fusion_reason", columnDefinition = "TEXT")
    private String fusionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_priority", nullable = false, length = 20)
    @Builder.Default
    private ReviewPriority reviewPriority = ReviewPriority.MEDIUM;

    @Column(name = "synced_to_label_studio")
    @Builder.Default
    private Boolean syncedToLabelStudio = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ReviewPriority {
        LOW,
        MEDIUM,
        HIGH
    }
}
