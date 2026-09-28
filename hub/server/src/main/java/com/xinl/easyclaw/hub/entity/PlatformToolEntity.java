package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台工具目录实体（platform_tools 表）：工具为平台级内置目录（由 Java Seeder 对齐 web/api
 * ToolRegistry 播种，不可经 API 增删），组织只能通过 org_tool_settings 决定「是否启用」。
 * <p>{@code toolKey} 对齐 web/api ToolRegistry 的工具名，全局唯一；{@code toolGroup} 为展示分组
 * （file/memory/session/agent/shell/web/code/knowledge/blackboard 等）。{@code enabled} 为平台总开关，
 * 生效语义 = 平台 enabled AND 组织 enabled。公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "platform_tools",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_tool_key", columnNames = "tool_key"))
public class PlatformToolEntity extends BaseEntity {

    @Column(name = "tool_key", nullable = false, length = 64)
    private String toolKey;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 512)
    private String description = "";

    @Column(name = "tool_group", nullable = false, length = 32)
    private String toolGroup = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 平台总开关；生效 = 平台 enabled AND 组织 enabled。 */
    @Column(nullable = false)
    private Boolean enabled = true;
}
