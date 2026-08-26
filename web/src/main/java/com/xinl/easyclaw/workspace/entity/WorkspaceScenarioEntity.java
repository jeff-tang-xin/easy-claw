package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工作区 ↔ 场景 激活关系（每个工作区最多激活一个场景）
 * <p>
 * 场景激活后，WorkspaceManager 构建 Agent 时会把场景提示词/编排工作流
 * 注入 system prompt；重启后端依然生效（本表持久化）。
 */
@Entity
@Table(name = "workspace_scenarios", indexes = {
    @Index(name = "idx_ws_scenarios_workspace", columnList = "workspace_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceScenarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @PrePersist
    protected void onCreate() {
        activatedAt = Instant.now();
    }
}
