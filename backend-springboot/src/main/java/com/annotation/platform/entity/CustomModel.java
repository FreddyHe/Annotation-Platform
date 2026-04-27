package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "custom_models")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CustomModel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String modelName;

    @Column
    private Long projectId;

    @Column(length = 120)
    private String targetClassName;

    @Column(length = 50)
    @Builder.Default
    private String datasetSource = "ROBOFLOW";

    @Column(length = 1000)
    private String datasetUri;

    @Column(length = 500)
    private String modelPath;

    @Column(length = 500)
    private String datasetPath;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";
    // 状态机：PENDING → DOWNLOADING → CONVERTING → TRAINING → COMPLETED
    //                                                       → FAILED

    @Column(length = 2000)
    private String statusMessage;

    private Integer epochs;
    private Integer batchSize;
    private Integer imageSize;
    private Double learningRate;

    @Builder.Default
    private Boolean usePretrained = true;

    @Builder.Default
    private Double progress = 0.0;

    private Double mapScore;
    private Double precisionScore;
    private Double recallScore;

    @Column(length = 2000)
    private String downloadCommand;

    @Column(columnDefinition = "TEXT")
    private String trainingLog;

    @Column(length = 50)
    private String datasetFormat;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
