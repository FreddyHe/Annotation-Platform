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
@Table(name = "project_requirement", indexes = {
        @Index(name = "idx_project_requirement_project_id", columnList = "project_id"),
        @Index(name = "idx_project_requirement_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_project_requirement_project"))
    private Project project;

    @Column(name = "raw_user_text", columnDefinition = "TEXT")
    private String rawUserText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_user_labels_json", columnDefinition = "json")
    private List<String> rawUserLabelsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_schema_json", columnDefinition = "json")
    private Map<String, Object> parsedSchemaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_schema_json", columnDefinition = "json")
    private List<Map<String, Object>> labelSchemaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_pack_json", columnDefinition = "json")
    private Map<String, Object> promptPackJson;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
