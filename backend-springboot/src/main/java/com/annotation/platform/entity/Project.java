package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "organization_id", foreignKey = @ForeignKey(name = "fk_project_organization"))
    private Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_project_creator"))
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "total_images")
    @Builder.Default
    private Integer totalImages = 0;

    @Column(name = "processed_images")
    @Builder.Default
    private Integer processedImages = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "labels", columnDefinition = "json")
    private List<String> labels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_definitions", columnDefinition = "json")
    private java.util.Map<String, String> labelDefinitions;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProjectImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AnnotationTask> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ModelTrainingRecord> trainingRecords = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    @Column(name = "ls_project_id")
    private Long lsProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ls_project_status", length = 20)
    @Builder.Default
    private LsProjectStatus lsProjectStatus = LsProjectStatus.UNKNOWN;

    @Column(name = "current_round_id")
    private Long currentRoundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", length = 20)
    @Builder.Default
    private ProjectType projectType = ProjectType.LEGACY;

    public enum ProjectStatus {
        DRAFT,
        UPLOADING,
        DETECTING,
        CLEANING,
        SYNCING,
        COMPLETED,
        FAILED
    }

    public enum ProjectType {
        LEGACY,
        ITERATIVE
    }

    public enum LsProjectStatus {
        UNKNOWN,
        ACTIVE,
        DEAD,
        REPAIRED
    }
}
