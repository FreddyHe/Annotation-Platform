package com.annotation.platform.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "custom_model_classes")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CustomModelClass {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long modelId;

    @Column(nullable = false)
    private Integer classId;

    @Column(nullable = false, length = 200)
    private String className;

    @Column(length = 200)
    private String cnName;
}
