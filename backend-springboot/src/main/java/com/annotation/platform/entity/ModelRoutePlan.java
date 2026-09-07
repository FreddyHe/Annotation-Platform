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
@Table(name = "model_route_plan", indexes = {
        @Index(name = "idx_model_route_plan_project_id", columnList = "project_id"),
        @Index(name = "idx_model_route_plan_requirement_id", columnList = "requirement_id"),
        @Index(name = "idx_model_route_plan_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRoutePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_model_route_plan_project"))
    private Project project;

    @Column(name = "dataset_id", nullable = false, length = 120)
    private String datasetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", foreignKey = @ForeignKey(name = "fk_model_route_plan_requirement"))
    private ProjectRequirement requirement;

    @Column(name = "primary_model_id", nullable = false, length = 120)
    private String primaryModelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auxiliary_model_ids_json", columnDefinition = "json")
    private List<String> auxiliaryModelIdsJson;

    @Column(name = "strategy", nullable = false, length = 80)
    @Builder.Default
    private String strategy = "HYBRID_TEACHER_QUALITY";

    @Column(name = "priority_mode", nullable = false, length = 50)
    @Builder.Default
    private String priorityMode = "quality_first";

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_json", columnDefinition = "json")
    private Map<String, Object> scoreJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
