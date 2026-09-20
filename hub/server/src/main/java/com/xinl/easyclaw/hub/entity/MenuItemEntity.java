package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 公共菜单项实体（menu_items 表）：下发给 spoke 的菜单配置，绑定到工作区（因工作区↔project 1:1，
 * 等价于按 project）。<b>与组织无关</b>，spoke 不按自身单独控制——同一工作区的菜单对所有 spoke 通用。
 * <p>{@code menuKey}（列 menu_key，key 为 PG 保留字）为 spoke 侧稳定标识，同工作区内唯一；
 * {@code parentId} 支持层级（null=顶层）；{@code requiredPerm} 为空表示不额外要求权限码，
 * {@code visibleRoles} 逗号分隔角色、为空表示全部角色可见。公共字段见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "menu_items", uniqueConstraints = @UniqueConstraint(name = "uk_menu_workspace_key",
        columnNames = {"workspace_id", "menu_key"}))
public class MenuItemEntity extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "menu_key", nullable = false, length = 64)
    private String menuKey;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(length = 64)
    private String icon;

    @Column(length = 255)
    private String path;

    /** 可见性权限码，空=不要求。 */
    @Column(name = "required_perm", nullable = false, length = 64)
    private String requiredPerm = "";

    /** 逗号分隔角色，空=全部角色可见。 */
    @Column(name = "visible_roles", nullable = false, length = 255)
    private String visibleRoles = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;
}
