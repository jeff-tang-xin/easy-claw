package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织菜单可见性开关（org_menu_settings 表）：惰性行——无行 = 默认可见；行存在时以 visible 为准。
 * 生效语义 = 平台 enabled AND 组织 visible。唯一约束 (org_id, menu_id)；无外键，一致性由应用层保证。
 * 公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "org_menu_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_org_menu_setting", columnNames = {"org_id", "menu_id"}))
public class OrgMenuSettingEntity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Column(nullable = false)
    private Boolean visible = true;
}
