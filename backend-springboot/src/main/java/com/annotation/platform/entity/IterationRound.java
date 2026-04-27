package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "iteration_rounds", indexes = {
        @Index(name = "idx_round_project_round", columnList = "project_id, round_number"),
        @Index(name = "idx_round_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IterationRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_round_project"))
    private Project project;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private RoundStatus status = RoundStatus.ACTIVE;

    @Column(name = "training_record_id")
    private Long trainingRecordId;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public enum RoundStatus {
        ACTIVE,
        TRAINING,
        DEPLOYED_READY,
        DEPLOYED,
        COLLECTING,
        REVIEWING,
        CLOSED
    }
}
