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

    @Column(name = "phase_name", nullable = false, length = 100)
    private String phaseName;

    @Column(name = "phase_description", columnDefinition = "TEXT")
    private String phaseDescription;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "estimated_days")
    private Integer estimatedDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tasks", columnDefinition = "json")
    private List<String> tasks;

    @Column(name = "deliverables", columnDefinition = "TEXT")
    private String deliverables;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlanStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", foreignKey = @ForeignKey(name = "fk_plan_assessment"))
    private FeasibilityAssessment assessment;

    public enum PlanStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        DELAYED
    }
}