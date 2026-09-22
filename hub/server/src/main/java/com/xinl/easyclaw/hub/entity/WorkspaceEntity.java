package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作区实体（workspaces 表）：project 面向 spoke 的扩展面，与 project <b>1:1 绑定</b>
 * （{@code uk_workspace_project}）。project 仍是纯归类锚点，工作区在其上叠加 spoke 侧配置（菜单等）。
 * org_id 冗余自 project，便于按组织列举与鉴权（一致性由应用层保证，无外键）。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "workspaces", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_project", columnNames = "project_id"))
@Deprecated
public class WorkspaceEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 128)
    private String name;

    /** active|archived */
    @Column(nullable = false, length = 20)
    private String status = "active";
}
