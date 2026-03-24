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
@Table(name = "dataset_search_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetSearchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_name", length = 200)
    private String categoryName;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "dataset_name", nullable = false, length = 200)
    private String datasetName;

    @Column(name = "dataset_url", length = 500)
    private String datasetUrl;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "json")
    private List<String> categories;

    @Column(name = "annotation_format", length = 50)
    private String annotationFormat;

    @Column(name = "license", length = 100)
    private String license;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "search_url", length = 500)
    private String searchUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", foreignKey = @ForeignKey(name = "fk_dataset_assessment"))
    private FeasibilityAssessment assessment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_assessment_id", foreignKey = @ForeignKey(name = "fk_dataset_category"))
    private CategoryAssessment categoryAssessment;
}
