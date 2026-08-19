package com.xinl.easyclaw.scenario.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 场景（Scenario）：GOAP 规划的能力集合。
 * <p>
 * 一个场景绑定了：
 * <ul>
 *   <li>来自内置 SubAgent 的 Action（用户勾选）</li>
 *   <li>来自 MCP Server 的工具（用户勾选）</li>
 *   <li>用户自定义的 PromptTemplateAction</li>
 *   <li>Skill 规范</li>
 * </ul>
 * Workspace 通过 scenarioId 关联一个场景，运行时由场景决定 GOAP 能看到哪些 Action。
 */
@Entity
@Table(name = "scenarios")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "icon", length = 32)
    private String icon;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /**
     * 预设 intent：coding / weekly-report / content-create / mail-triage / data-analysis / devops
     * 自定义场景为 "custom"
     */
    @Column(name = "intent", length = 32)
    @Builder.Default
    private String intent = "custom";

    /**
     * 内置 Action 绑定（JSON 数组）
     * [{"actionId": "code-analyze", "agentType": "coding", "enabled": true}, ...]
     */
    @Column(name = "action_bindings", columnDefinition = "TEXT")
    private String actionBindings;

    /**
     * MCP Server 绑定（JSON 数组）
     * [{"mcpName": "github-mcp", "enabled": true}, ...]
     */
    @Column(name = "mcp_bindings", columnDefinition = "TEXT")
    private String mcpBindings;

    /**
     * Skill 列表（JSON 数组）
     * ["karpathy-guidelines", "code-review-rules"]
     */
    @Column(name = "skills", columnDefinition = "TEXT")
    private String skills;

    /**
     * 直接启用的 SubAgent 类型（JSON 数组）
     * ["coding", "file", "content"]
     * 优先级高于从 actionBindings 推断；为 null/空时回退到推断模式
     */
    @Column(name = "enabled_agents", columnDefinition = "TEXT")
    private String enabledAgents;

    /**
     * 场景级 GOAP 规划约束（JSON Object，全配置化，不硬编码到 @Action）。
     * null / 空字符串 / 解析失败 → 宽松模式（只做 step 注册校验）。
     * 结构示例：
     * {
     *   "maxSteps": 3,
     *   "firstStepCannotBe": ["orchestrate", "verify-task"],
     *   "onlyOnce": ["code-task", "file-task"],
     *   "orderRules": {
     *     "verify-task": ["code-task", "devops-task"],
     *     "content-task": ["research", "mail-task", "data-task", "file-task"]
     *   }
     * }
     */
    @Column(name = "plan_constraints", columnDefinition = "TEXT")
    private String planConstraints;

    @Column(name = "is_preset")
    @Builder.Default
    private Boolean isPreset = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (isPreset == null) isPreset = false;
        if (intent == null) intent = "custom";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
