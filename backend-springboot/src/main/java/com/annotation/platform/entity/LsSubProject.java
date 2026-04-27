package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ls_sub_projects", indexes = {
        @Index(name = "idx_sub_project_project", columnList = "project_id"),
        @Index(name = "idx_sub_project_ls", columnList = "ls_project_id"),
        @Index(name = "idx_sub_project_type_status", columnList = "project_id, sub_type, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LsSubProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "ls_project_id", nullable = false, unique = true)
    private Long lsProjectId;

    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_type", nullable = false, length = 20)
    private SubType subType;

    @Column(name = "batch_number")
    private Integer batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING_REVIEW;

    @Column(name = "expected_tasks")
    @Builder.Default
    private Integer expectedTasks = 0;

    @Column(name = "reviewed_tasks")
    @Builder.Default
    private Integer reviewedTasks = 0;

    @Column(name = "round_id")
    private Long roundId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    public enum SubType {
        MAIN,
        INCREMENTAL
    }

    public enum Status {
        PENDING_REVIEW,
        PARTIAL,
        REVIEWED
    }
}
