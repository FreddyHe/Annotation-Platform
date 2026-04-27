package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "feasibility_assessments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeasibilityAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_name", nullable = false, length = 200)
    private String assessmentName;

    @Column(name = "raw_requirement", columnDefinition = "TEXT")
    private String rawRequirement;

    @Column(name = "structured_requirement", columnDefinition = "TEXT")
    private String structuredRequirement;

    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    @Column(name = "dataset_size")
    private Integer datasetSize;

    @Column(name = "category_count")
    private Integer categoryCount;

    @Column(name = "samples_per_category")
    private Integer samplesPerCategory;

    @Column(name = "image_quality", length = 50)
    private String imageQuality;

    @Column(name = "annotation_completeness")
    private Integer annotationCompleteness;

    @Column(name = "target_size", length = 50)
    private String targetSize;

    @Column(name = "background_complexity", length = 50)
    private String backgroundComplexity;

    @Column(name = "inter_class_similarity", length = 50)
    private String interClassSimilarity;

    @Column(name = "expected_accuracy")
    private Integer expectedAccuracy;

    @Column(name = "training_resource", length = 100)
    private String trainingResource;

    @Column(name = "time_budget_days")
    private Integer timeBudgetDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private AssessmentStatus status = AssessmentStatus.CREATED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_match_level")
    private DatasetMatchLevel datasetMatchLevel;

    @Column(name = "user_judgment_notes", columnDefinition = "TEXT")
    private String userJudgmentNotes;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_assessment_creator"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "organization_id", foreignKey = @ForeignKey(name = "fk_assessment_organization"))
    private Organization organization;

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CategoryAssessment> categoryAssessments = new ArrayList<>();

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OvdTestResult> ovdTestResults = new ArrayList<>();

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DatasetSearchResult> datasetSearchResults = new ArrayList<>();

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ResourceEstimation> resourceEstimations = new ArrayList<>();

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ImplementationPlan> implementationPlans = new ArrayList<>();
}
