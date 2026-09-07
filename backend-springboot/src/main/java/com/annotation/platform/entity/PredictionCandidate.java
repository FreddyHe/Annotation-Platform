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
import java.util.Map;

@Entity
@Table(name = "prediction_candidate", indexes = {
        @Index(name = "idx_prediction_candidate_job_id", columnList = "job_id"),
        @Index(name = "idx_prediction_candidate_image_id", columnList = "image_id"),
        @Index(name = "idx_prediction_candidate_label", columnList = "label")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, foreignKey = @ForeignKey(name = "fk_prediction_candidate_job"))
    private AutoLabelJob job;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox_json", columnDefinition = "json")
    private List<Double> bboxJson;

    @Column(name = "score")
    private Double score;

    @Column(name = "source_model", nullable = false, length = 120)
    private String sourceModel;

    @Column(name = "model_version", length = 120)
    private String modelVersion;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_output_json", columnDefinition = "json")
    private Map<String, Object> rawOutputJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
