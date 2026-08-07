package com.xinl.easyclaw.tool.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工具/插件定义实体
 * <p>
 * 支持三种实现方式：BUILTIN（内置）、MCP（外部 MCP）、SCRIPT（脚本/Jar 上传）
 */
@Entity
@Table(name = "tool_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", unique = true, nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "tool_group", length = 64)
    private String toolGroup;

    /**
     * 参数定义 (JSON Schema)
     */
    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    /**
     * 实现方式: BUILTIN / MCP / SCRIPT
     */
    @Column(name = "implementation", length = 32)
    private String implementation;

    @Column(name = "implementation_config", columnDefinition = "TEXT")
    private String implementationConfig;

    @Column(name = "is_enabled")
    private Boolean enabled;

    @Column(name = "is_system")
    private Boolean isSystem;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (enabled == null) {
            enabled = true;
        }
        if (isSystem == null) {
            isSystem = false;
        }
    }
}
