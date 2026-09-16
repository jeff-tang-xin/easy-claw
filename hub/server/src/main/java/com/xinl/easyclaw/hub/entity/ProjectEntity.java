package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 项目实体（projects 表）：组织内的归类锚点，后续 docs/知识库/blackboard 都挂载 project_id。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(name = "uk_project_org_slug", columnNames = {"org_id", "slug"}))
public class ProjectEntity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    /** private|team|public */
    @Column(nullable = false, length = 20)
    private String visibility = "team";

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** active|archived */
    @Column(nullable = false, length = 20)
    private String status = "active";
}
