package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vlm_evaluation_details")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VlmEvaluationDetail {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_score_id", nullable = false)
    private VlmQualityScore qualityScore;
    
    @Column(name = "bbox_idx", nullable = false)
    private Integer bboxIdx;
    
    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect;
    
    @Column(name = "cropped_image_path", length = 500)
    private String croppedImagePath;
    
    @Column(name = "question", columnDefinition = "TEXT")
    private String question;
    
    @Column(name = "vlm_answer", length = 200)
    private String vlmAnswer;
    
    @Column(name = "bbox_json", columnDefinition = "TEXT")
    private String bboxJson;
    
    @Column(name = "error_reason", length = 500)
    private String errorReason;
}
