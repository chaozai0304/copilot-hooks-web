package com.copilot.hooks.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "model_configs")
@Getter
@Setter
public class ModelConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String provider = "OpenAI Compatible";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String baseUrl;

    @Column(columnDefinition = "TEXT")
    private String apiKeyCipher;

    @Column(nullable = false, length = 160)
    private String chatModel;

    @Column(nullable = false, length = 160)
    private String embeddingModel;

    @Column(nullable = false)
    private Integer embeddingDimensions = 1536;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean defaultConfig = false;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
