package com.annotation.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_model_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "vlm_api_key", length = 500)
    private String vlmApiKey;

    @Column(name = "vlm_base_url", length = 500)
    private String vlmBaseUrl;

    @Column(name = "vlm_model_name", length = 200)
    private String vlmModelName;

    @Column(name = "llm_api_key", length = 500)
    private String llmApiKey;

    @Column(name = "llm_base_url", length = 500)
    private String llmBaseUrl;

    @Column(name = "llm_model_name", length = 200)
    private String llmModelName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

