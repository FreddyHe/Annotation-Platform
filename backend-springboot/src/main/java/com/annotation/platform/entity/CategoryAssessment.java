package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "category_assessments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_category_assessment"))
    private FeasibilityAssessment assessment;

    @Column(name = "category_name", nullable = false, length = 200)
    private String categoryName;

    @Column(name = "category_name_en", length = 200)
    private String categoryNameEn;

    @Column(name = "category_type", length = 50)
    private String categoryType;

    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    @Column(name = "view_angle", length = 100)
    private String viewAngle;

    @Column(name = "environment_constraints", columnDefinition = "TEXT")
    private String environmentConstraints;

    @Column(name = "performance_requirements", columnDefinition = "TEXT")
    private String performanceRequirements;

    @Enumerated(EnumType.STRING)
    @Column(name = "feasibility_bucket", nullable = false, length = 50)
    @Builder.Default
    private FeasibilityBucket feasibilityBucket = FeasibilityBucket.PENDING;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
