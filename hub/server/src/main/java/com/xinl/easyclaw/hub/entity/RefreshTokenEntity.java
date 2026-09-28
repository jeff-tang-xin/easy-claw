package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 刷新令牌实体（refresh_tokens 表）：只存 token 的 hash，不存明文，支持吊销。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "refresh_tokens", indexes = @Index(name = "idx_rt_user", columnList = "user_id"))
public class RefreshTokenEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 128)
    private String device;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;
}
