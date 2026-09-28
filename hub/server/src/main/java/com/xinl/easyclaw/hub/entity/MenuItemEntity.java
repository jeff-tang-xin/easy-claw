package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台菜单目录项实体（menu_items 表）：菜单为平台级内置目录，新增/修改/删除仅 platformAdmin 可做；
 * 组织只能通过 org_menu_settings 决定「是否显示」。
 * <p>{@code menuKey}（列 menu_key，key 为 PG 保留字）为 spoke 侧稳定标识，全局唯一、创建后不可改
 * （服务端裁决）；{@code parentId} 支持层级（null=顶层）；{@code requiredPerm} 为空表示不额外要求
 * 权限码，{@code visibleRoles} 逗号分隔角色、为空表示全部角色可见。{@code enabled} 为平台总开关，
 * 生效语义 = 平台 enabled AND 组织 visible。公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "menu_items", uniqueConstraints = @UniqueConstraint(name = "uk_menu_key", columnNames = "menu_key"))
public class MenuItemEntity extends BaseEntity {

    @Column(name = "menu_key", nullable = false, length = 64)
    private String menuKey;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(nullable = false, length = 64)
    private String icon = "";

    @Column(nullable = false, length = 255)
    private String path = "";

    @Column(name = "parent_id")
    private Long parentId;

    /** 可见性权限码，空=不要求。 */
    @Column(name = "required_perm", nullable = false, length = 64)
    private String requiredPerm = "";

    /** 逗号分隔角色，空=全部角色可见。 */
    @Column(name = "visible_roles", nullable = false, length = 255)
    private String visibleRoles = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 平台总开关；生效 = 平台 enabled AND 组织 visible。 */
    @Column(nullable = false)
    private Boolean enabled = true;
}
