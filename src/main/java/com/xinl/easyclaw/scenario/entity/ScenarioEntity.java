package com.xinl.easyclaw.scenario.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 场景（Scenario）定义实体
 * <p>
 * 场景 = 一套面向特定任务形态的智能体运行配置：
 * <ul>
 *   <li>single 模式：注入场景系统提示词（人格/规范/流程），主智能体独立工作</li>
 *   <li>team 模式：多智能体编排 —— 在场景提示词之上，定义一组基于子 Agent 的
 *       工作流步骤（串行阶段 + 并行分组），由主智能体作为编排者调度执行</li>
 * </ul>
 * 激活关系持久化在 {@code workspace_scenarios}（每工作区一个激活场景）。
 */
@Entity
@Table(name = "scenarios", indexes = {
    @Index(name = "idx_scenarios_name", columnList = "name", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 英文标识（唯一） */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", length = 64)
    private String displayName;

    /** 展示图标（emoji） */
    @Column(length = 16)
    private String icon;

    @Column(length = 500)
    private String description;

    /** single = 单智能体；team = 多智能体编排 */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String mode = "single";

    /** 场景系统提示词（追加到主智能体 system prompt 之后） */
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    /**
     * team 模式的编排工作流（JSON）：
     * {"steps":[{"subagent":"planner","instruction":"拆解任务","parallel":false},
     *           {"subagent":"coder","instruction":"实现","parallel":true}]}
     * parallel=true 表示与上一步并行执行。
     */
    @Column(columnDefinition = "TEXT")
    private String workflow;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean active = true;

    /** 系统内置场景（种子播种，删除时联动清理激活关系） */
    @Column(name = "is_builtin")
    @Builder.Default
    private Boolean builtin = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (active == null) active = true;
        if (builtin == null) builtin = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
