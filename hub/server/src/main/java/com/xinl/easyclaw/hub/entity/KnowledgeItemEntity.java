package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识条目当前态（knowledge_items 表，V8）：归属项目（project_id），topic 项目内唯一（仅 active）。
 * version 为乐观锁版本号，每次内容/topic/summary 变更 +1；status=deleted 为软删（历史保留，可重建同名）。
 *
 * <p>向量列 {@code embedding vector(1536)} <b>刻意不映射为 Java 字段</b>：Hibernate 无原生 vector 类型，
 * 且生产 {@code ddl-auto=none}、表结构以 Flyway V8 为准；向量由 A3-S3 经原生 SQL 读写。
 * 本实体只映射两个标量状态列 embeddingModel/embeddingStatus，SQLite 集成测试因此不触碰 vector 列。
 * 无外键（V4 决策），project/user 一致性由应用层保证。
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_items")
public class KnowledgeItemEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(nullable = false, length = 500)
    private String summary = "";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content = "";

    @Column(nullable = false)
    private Long version = 1L;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 最近一次编辑者（首版等于 owner）。 */
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    /** active | deleted */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** 生成当前向量所用模型（provider/model），换模型据此识别陈旧向量；未生成时为 null。 */
    @Column(name = "embedding_model", length = 120)
    private String embeddingModel;

    /** pending | ready | failed：语义索引就绪状态；S1 写入后恒为 pending，由 A3-S3 推进。 */
    @Column(name = "embedding_status", nullable = false, length = 20)
    private String embeddingStatus = "pending";
}
