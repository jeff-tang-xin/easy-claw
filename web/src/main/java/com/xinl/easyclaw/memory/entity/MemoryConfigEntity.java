package com.xinl.easyclaw.memory.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 记忆配置实体（用户级）
 * <p>
 * 每个用户可独立配置记忆提取开关、召回数量、多维权重、遗忘天数等参数
 */
@Entity
@Table(name = "memory_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false, length = 64)
    private String userId;

    @Column(name = "extraction_enabled")
    private Boolean extractionEnabled;

    @Column(name = "recall_limit")
    private Integer recallLimit;

    @Column(name = "recency_weight")
    private Double recencyWeight;

    @Column(name = "content_weight")
    private Double contentWeight;

    @Column(name = "context_weight")
    private Double contextWeight;

    @Column(name = "emotional_weight")
    private Double emotionalWeight;

    @Column(name = "associative_weight")
    private Double associativeWeight;

    @Column(name = "insight_weight")
    private Double insightWeight;

    @Column(name = "forget_days")
    private Integer forgetDays;

    @Column(name = "min_reference_count")
    private Integer minReferenceCount;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = Instant.now();
        if (extractionEnabled == null) {
            extractionEnabled = true;
        }
        if (recallLimit == null) {
            recallLimit = 10;
        }
        if (forgetDays == null) {
            forgetDays = 30;
        }
        if (minReferenceCount == null) {
            minReferenceCount = 3;
        }
        if (recencyWeight == null) {
            recencyWeight = 0.25;
        }
        if (contentWeight == null) {
            contentWeight = 0.30;
        }
        if (contextWeight == null) {
            contextWeight = 0.15;
        }
        if (emotionalWeight == null) {
            emotionalWeight = 0.10;
        }
        if (associativeWeight == null) {
            associativeWeight = 0.10;
        }
        if (insightWeight == null) {
            insightWeight = 0.10;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
