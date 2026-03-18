package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "resource_estimations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceEstimation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_resource_estimation_assessment"))
    private FeasibilityAssessment assessment;

    @Column(name = "category_name", nullable = false, length = 200)
    private String categoryName;

    @Enumerated(EnumType.STRING)
    @Column(name = "feasibility_bucket", length = 50)
    private FeasibilityBucket feasibilityBucket;

    @Column(name = "estimated_images")
    private Integer estimatedImages;

    @Column(name = "estimated_man_days")
    private Integer estimatedManDays;

    @Column(name = "gpu_hours")
    private Integer gpuHours;

    @Column(name = "iteration_count")
    private Integer iterationCount;

    @Column(name = "estimated_total_days")
    private Integer estimatedTotalDays;

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
