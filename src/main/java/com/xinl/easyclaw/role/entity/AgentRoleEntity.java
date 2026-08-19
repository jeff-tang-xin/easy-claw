package com.xinl.easyclaw.role.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * AI 角色定义实体
 * <p>
 * 存储角色的名称、角色设定、目标、背景故事、温度参数、模型等配置
 */
@Entity
@Table(name = "agent_roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "role_desc", length = 500)
    private String role;

    @Column(name = "goal", length = 500)
    private String goal;

    @Column(name = "backstory", columnDefinition = "TEXT")
    private String backstory;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "model", length = 64)
    private String model;

    /**
     * 角色类型：
     * <ul>
     *   <li>MARKDOWN：使用 MarkdownSubagent 动态创建 Agent（默认）</li>
     *   <li>BUILTIN：关联一个已有的 @Agent 类</li>
     * </ul>
     */
    @Column(name = "role_type", length = 16)
    @Builder.Default
    private String roleType = "MARKDOWN";

    /** BUILTIN 类型时，关联的 @Agent 类全限定名 */
    @Column(name = "agent_class_name", length = 255)
    private String agentClassName;

    /** 角色默认推荐的 Skill 列表（JSON 数组） */
    @Column(name = "default_skills", columnDefinition = "TEXT")
    private String defaultSkills;

    /** 角色允许调用的子 Agent 类型列表（JSON 数组） */
    @Column(name = "allowed_agents", columnDefinition = "TEXT")
    private String allowedAgents;

    /** 角色允许调用的 MCP 工具/服务列表（JSON 数组） */
    @Column(name = "allowed_mcp", columnDefinition = "TEXT")
    private String allowedMcp;

    @Column(name = "is_active")
    private Boolean active;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (active == null) {
            active = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
