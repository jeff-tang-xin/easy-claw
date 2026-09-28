package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识条目版本历史（knowledge_item_events 表，V8）：追加型、不可变（只有 created_at，不继承 BaseEntity）。
 * 每次创建/内容更新追加一行快照（version/topic/summary/content/actor）。条目软删后历史仍留痕可查。
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_item_events")
public class KnowledgeItemEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** 冗余项目 id：条目软删后仍可据此定位项目做读权限校验。 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(nullable = false, length = 500)
    private String summary = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content = "";

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
