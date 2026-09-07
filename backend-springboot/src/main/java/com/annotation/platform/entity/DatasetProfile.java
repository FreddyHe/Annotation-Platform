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
@Table(name = "dataset_profile", indexes = {
        @Index(name = "idx_dataset_profile_project_id", columnList = "project_id"),
        @Index(name = "idx_dataset_profile_dataset_id", columnList = "dataset_id"),
        @Index(name = "idx_dataset_profile_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_dataset_profile_project"))
    private Project project;

    @Column(name = "dataset_id", nullable = false, length = 120)
    private String datasetId;

    @Column(name = "num_images")
    @Builder.Default
    private Integer numImages = 0;

    @Column(name = "sample_count")
    @Builder.Default
    private Integer sampleCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_json", columnDefinition = "json")
    private Map<String, Object> profileJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_warnings_json", columnDefinition = "json")
    private List<Map<String, Object>> qualityWarningsJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
