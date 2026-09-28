package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台功能开关目录实体（feature_flags 表）：开关为平台级内置目录，新增/修改/删除仅 platformAdmin
 * 可做；组织只能通过 org_flag_settings 决定「是否启用」。
 * <p>{@code flagKey} 为 spoke 侧稳定标识，全局唯一、创建后不可改（服务端裁决）。
 * {@code enabled} 为平台总开关，生效语义 = 平台 enabled AND 组织 enabled。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "feature_flags",
        uniqueConstraints = @UniqueConstraint(name = "uk_feature_flag_key", columnNames = "flag_key"))
public class FeatureFlagEntity extends BaseEntity {

    @Column(name = "flag_key", nullable = false, length = 64)
    private String flagKey;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(nullable = false, length = 512)
    private String description = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 平台总开关；生效 = 平台 enabled AND 组织 enabled。 */
    @Column(nullable = false)
    private Boolean enabled = true;
}
