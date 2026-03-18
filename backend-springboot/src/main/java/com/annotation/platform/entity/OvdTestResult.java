package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ovd_test_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OvdTestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    @Column(name = "detected_count")
    private Integer detectedCount;

    @Column(name = "average_confidence")
    private Double averageConfidence;

    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;

    @Column(name = "annotated_image_path", length = 500)
    private String annotatedImagePath;

    @CreationTimestamp
    @Column(name = "test_time", nullable = false, updatable = false)
    private LocalDateTime testTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ovd_test_assessment"))
    private FeasibilityAssessment assessment;

    @OneToMany(mappedBy = "ovdTestResult", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VlmQualityScore> qualityScores = new ArrayList<>();
}
