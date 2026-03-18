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
@Table(name = "resource_estimations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceEstimation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "estimated_images")
    private Integer estimatedImages;

    @Column(name = "estimated_man_days")
    private Integer estimatedManDays;

    @Column(name = "hardware_requirements", columnDefinition = "TEXT")
    private String hardwareRequirements;

    @Column(name = "estimated_days")
    private Integer estimatedDays;

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_assessment_id", foreignKey = @ForeignKey(name = "fk_resource_category"))
    private CategoryAssessment categoryAssessment;
}