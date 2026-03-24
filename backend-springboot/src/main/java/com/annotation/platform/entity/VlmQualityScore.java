package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "vlm_quality_scores")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmQualityScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ovd_test_result_id", nullable = false, foreignKey = @ForeignKey(name = "fk_vlm_quality_ovd_result"))
    private OvdTestResult ovdTestResult;

    @Column(name = "total_gt_estimated")
    private Integer totalGtEstimated;

    @Column(name = "detected")
    private Integer detected;

    @Column(name = "false_positive")
    private Integer falsePositive;

    @Column(name = "precision_estimate")
    private Double precisionEstimate;

    @Column(name = "recall_estimate")
    private Double recallEstimate;

    @Column(name = "bbox_quality", length = 20)
    private String bboxQuality;

    @Column(name = "overall_verdict", length = 50)
    private String overallVerdict;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "qualityScore", cascade = CascadeType.ALL, orphanRemoval = true)
    @lombok.Builder.Default
    private java.util.List<VlmEvaluationDetail> evaluationDetails = new java.util.ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
