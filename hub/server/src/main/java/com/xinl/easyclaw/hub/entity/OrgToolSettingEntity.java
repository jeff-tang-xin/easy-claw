package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织工具启用行（org_tool_settings 表）：惰性行——无行 = 默认启用；行存在时以 enabled 为准。
 * 生效语义 = 平台 enabled AND 组织 enabled。唯一约束 (org_id, tool_id)；无外键，一致性由应用层保证。
 * 公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "org_tool_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_org_tool_setting", columnNames = {"org_id", "tool_id"}))
public class OrgToolSettingEntity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "tool_id", nullable = false)
    private Long toolId;

    @Column(nullable = false)
    private Boolean enabled = true;
}
