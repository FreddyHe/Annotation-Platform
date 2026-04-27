package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectConfig {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "high_pool_threshold")
    @Builder.Default
    private Double highPoolThreshold = 0.8;

    @Column(name = "low_pool_threshold")
    @Builder.Default
    private Double lowPoolThreshold = 0.4;

    @Column(name = "enable_auto_vlm_judge")
    @Builder.Default
    private Boolean enableAutoVlmJudge = true;

    @Column(name = "vlm_quota_per_round")
    @Builder.Default
    private Integer vlmQuotaPerRound = 500;

    @Column(name = "auto_trigger_retrain")
    @Builder.Default
    private Boolean autoTriggerRetrain = false;

    @Column(name = "retrain_min_samples")
    @Builder.Default
    private Integer retrainMinSamples = 200;

    @Column(name = "low_b_batch_size")
    @Builder.Default
    private Integer lowBBatchSize = 100;
}
