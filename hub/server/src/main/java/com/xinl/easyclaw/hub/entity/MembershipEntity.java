package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织成员实体（memberships 表）：用户-组织多对多 + 角色。唯一约束 (org_id, user_id)。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "memberships", uniqueConstraints = @UniqueConstraint(name = "uk_membership", columnNames = {"org_id", "user_id"}))
public class MembershipEntity extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** owner|admin|member|guest */
    @Column(nullable = false, length = 20)
    private String role = "member";
}
