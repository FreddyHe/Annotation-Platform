package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "implementation_plans")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImplementationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_plan_assessment"))
    private FeasibilityAssessment assessment;

    @Column(name = "phase_order", nullable = false)
    private Integer phaseOrder;

    @Column(name = "phase_name", nullable = false, length = 100)
    private String phaseName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "estimated_days")
    private Integer estimatedDays;

    @Column(name = "tasks", columnDefinition = "TEXT")
    private String tasks;

    @Column(name = "deliverables", columnDefinition = "TEXT")
    private String deliverables;

    @Column(name = "dependencies", length = 500)
    private String dependencies;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
