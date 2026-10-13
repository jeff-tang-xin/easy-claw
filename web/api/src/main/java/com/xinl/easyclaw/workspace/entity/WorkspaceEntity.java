package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "workspaces", indexes = {
    @Index(name = "idx_workspaces_path", columnList = "path", unique = true),
    @Index(name = "idx_workspaces_user_id", columnList = "user_id"),
    @Index(name = "idx_workspaces_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceEntity {
    @Id
    @Column(name = "id")
    private String id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(nullable = false)
    private String name;
    
    private String description;
    
    @Column(nullable = false, unique = true)
    private String path;
    
    @Column(length = 20)
    @Builder.Default
    private String status = "active";

    /**
     * 工作区形态分类，取值与场景模式（{@code ScenarioEntity.mode}）对齐：
     * {@code single}（SOLO，单智能体）/ {@code team}（团队编排）/ {@code schedule}（定时任务）。
     * <p>决定该工作区允许绑定哪一类场景（类型必须一致）。ddl-auto:update 自动加列，
     * 存量行取默认值 {@code single}（既有工作区全部归入 SOLO）。
     */
    @Column(nullable = false, length = 20,columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'single'")
    @Builder.Default
    private String type = "single";

    /**
     * 归属的 hub 项目（cloud 模式）：cloud 模式下创建工作区时必选并随同步请求携带，
     * hub 端据此把知识库/黑板数据归属到项目；本地模式恒 null。ddl-auto:update 自动加列。
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * 工作区绑定的数据库资源（hub 下发的 db 连接 serverKey，单选，可空）。
     * <p>
     * <b>绑定即能力</b>：非空时本工作区的智能体获得 db 内置能力实例（{@code db_query} /
     * {@code db_schema} / {@code db_status}），目标锁定为该库——工具面不暴露连接标识，模型
     * 无需也无法指定连哪个库。空 = 本工作区没有数据库能力（这些工具根本不出现）。
     * <p>
     * 为什么是单选：同一内置类型在一个工作区只能装一个实例（框架 ToolRegistry 按名注册，
     * 同名互相覆盖）。要多库并行请建多个工作区，或走专属 db 工作区（那条通道支持跨库）。
     */
    @Column(name = "db_server_key", length = 64)
    private String dbServerKey;

    /**
     * 工作区绑定的运维服务器（hub 下发的 serverKey，单选，可空）。
     * <p>
     * 非空时本工作区的智能体获得 {@code remote_shell} 能力，目标锁定为该机器。安全语义不变：
     * 每次执行逐条弹确认（不可被白名单/永久允许摘除），人当前终端连的是另一台时拒绝执行（fail-closed）。
     * <p>
     * 语义是「这个 AI 工作区默认操作哪台」，不是「能操作哪几台」——多台请建多个工作区（会话、
     * 知识库、审计天然隔离），或走专属 ops 工作区（多 tab 同时连多台）。
     */
    @Column(name = "ops_server_key", length = 64)
    private String opsServerKey;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
}
