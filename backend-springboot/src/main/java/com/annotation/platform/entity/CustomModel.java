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
    private Double mapScore;

    @Column(length = 2000)
    private String downloadCommand;

    @Column(length = 50)
    private String datasetFormat;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
