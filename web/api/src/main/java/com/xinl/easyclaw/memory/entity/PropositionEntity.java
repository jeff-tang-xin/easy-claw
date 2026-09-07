package com.xinl.easyclaw.memory.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 记忆命题实体 - 长期记忆持久化
 * <p>
 * JSON 字段以 String 存储以确保 SQLite 兼容性
 */
@Entity
@Table(name = "propositions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PropositionType type;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_accessed")
    private Instant lastAccessed;

    @Column(name = "reference_count")
    private Integer referenceCount;

    /**
     * 向量嵌入 (1536 维), 以 JSON 文本存储
     */
    @Column(name = "embeddings", columnDefinition = "TEXT")
    private String embeddingsJson;

    /**
     * 情感标签 (JSON 数组文本)
     */
    @Column(name = "emotion_tags", columnDefinition = "TEXT")
    private String emotionTagsJson;

    /**
     * 话题列表 (JSON 数组文本)
     */
    @Column(name = "topics", columnDefinition = "TEXT")
    private String topicsJson;

    /** 关联的对话 ID */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        lastAccessed = Instant.now();
        if (referenceCount == null) {
            referenceCount = 0;
        }
    }
}
