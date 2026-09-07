package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "model_registry", indexes = {
        @Index(name = "idx_model_registry_model_id", columnList = "model_id"),
        @Index(name = "idx_model_registry_status", columnList = "status"),
        @Index(name = "idx_model_registry_model_type", columnList = "model_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false, unique = true, length = 120)
    private String modelId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "version", length = 100)
    private String version;

    @Column(name = "model_type", nullable = false, length = 80)
    private String modelType;

    @Column(name = "task_type", nullable = false, length = 80)
    private String taskType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "domain_tags_json", columnDefinition = "json")
    private List<String> domainTagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "classes_json", columnDefinition = "json")
    private List<String> classesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases_json", columnDefinition = "json")
    private List<String> aliasesJson;

    @Column(name = "endpoint", length = 500)
    private String endpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.UNAVAILABLE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json", columnDefinition = "json")
    private Map<String, Object> metricsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_json", columnDefinition = "json")
    private Map<String, Object> resourceJson;

    @Column(name = "license_info", length = 500)
    private String licenseInfo;

    @Column(name = "unavailable_reason", length = 1000)
    private String unavailableReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum Status {
        AVAILABLE,
        UNAVAILABLE,
        DISABLED
    }
}
