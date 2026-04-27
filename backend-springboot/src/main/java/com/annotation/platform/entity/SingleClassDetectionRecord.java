package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "single_class_detection_records", indexes = {
        @Index(name = "idx_single_class_detection_user_id", columnList = "user_id"),
        @Index(name = "idx_single_class_detection_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleClassDetectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "model_id", length = 80)
    private String modelId;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "model_path", length = 1000)
    private String modelPath;

    @Column(name = "class_id")
    private Integer classId;

    @Column(name = "class_name", length = 200)
    private String className;

    @Column(name = "image_path", length = 1000)
    private String imagePath;

    @Column(name = "detection_count")
    private Integer detectionCount;

    @Column(name = "average_confidence")
    private Double averageConfidence;

    @Column(name = "confidence_threshold")
    private Double confidenceThreshold;

    @Column(name = "iou_threshold")
    private Double iouThreshold;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
