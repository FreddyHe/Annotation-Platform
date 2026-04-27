package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "edge_deployments", indexes = {
        @Index(name = "idx_deployment_project_status", columnList = "project_id, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "model_record_id", nullable = false)
    private Long modelRecordId;

    @Column(name = "edge_node_name", length = 100)
    private String edgeNodeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeploymentStatus status = DeploymentStatus.DEPLOYING;

    @Column(name = "deployed_at", nullable = false)
    @Builder.Default
    private LocalDateTime deployedAt = LocalDateTime.now();

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @Column(name = "previous_deployment_id")
    private Long previousDeploymentId;

    public enum DeploymentStatus {
        DEPLOYING,
        ACTIVE,
        ROLLED_BACK,
        FAILED
    }
}
