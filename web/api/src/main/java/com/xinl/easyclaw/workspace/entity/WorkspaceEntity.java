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
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String type = "single";

    /**
     * 归属的 hub 项目（cloud 模式）：cloud 模式下创建工作区时必选并随同步请求携带，
     * hub 端据此把知识库/黑板数据归属到项目；本地模式恒 null。ddl-auto:update 自动加列。
     */
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
}
