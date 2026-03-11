package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "model_training_records", indexes = {
    @Index(name = "idx_project_id", columnList = "project_id"),
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_task_id", columnList = "task_id"),
    @Index(name = "idx_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_training_record_project"))
    private Project project;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_id", nullable = false, unique = true, length = 100)
    private String taskId;

    @Column(name = "run_name", nullable = false, length = 200)
    private String runName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TrainingStatus status;

    @Column(name = "epochs", nullable = false)
    private Integer epochs;

    @Column(name = "batch_size", nullable = false)
    private Integer batchSize;

    @Column(name = "image_size", nullable = false)
    private Integer imageSize;

    @Column(name = "model_type", nullable = false, length = 50)
    private String modelType;

    @Column(name = "dataset_path", length = 500)
    private String datasetPath;

    @Column(name = "output_dir", length = 500)
    private String outputDir;

    @Column(name = "best_model_path", length = 500)
    private String bestModelPath;

    @Column(name = "last_model_path", length = 500)
    private String lastModelPath;

    @Column(name = "log_file_path", length = 500)
    private String logFilePath;

    @Column(name = "map50")
    private Double map50;

    @Column(name = "map50_95")
    private Double map50_95;

    @Column(name = "precision")
    private Double precision;

    @Column(name = "recall")
    private Double recall;

    @Column(name = "total_images")
    private Integer totalImages;

    @Column(name = "total_annotations")
    private Integer totalAnnotations;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "test_results", columnDefinition = "JSON")
    private String testResults;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum TrainingStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
