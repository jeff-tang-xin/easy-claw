package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户实体（users 表）。密码只存 BCrypt hash。公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(unique = true, length = 128)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    /** active|disabled */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** 平台管理员：可管理用户（添加/列表）。由启动引导或既有管理员授予。 */
    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin = false;

    /** 首次登录强制改密：true 时除改密/登出/查询自身外的 API 一律拒绝。 */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;
}
