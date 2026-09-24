package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 运维服务器用户授权（ops_server_grants，V18）：platformAdmin 把某 hub 用户授权到某服务器，
 * 带时效窗口（valid_from/valid_until）。一人一服务器一条（uk），续期 = 更新 valid_until；
 * 过期行保留作历史，下发查询按 now 过滤。org_id 冗余自服务器归属，便于按组织鉴权；
 * 全库无外键，一致性由应用层保证。公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "ops_server_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_ops_grant_server_user", columnNames = {"server_id", "user_id"}))
public class OpsServerGrantEntity extends BaseEntity {

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 冗余自服务器归属组织（一致性由应用层保证，无外键）。 */
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    /** 操作人（platformAdmin）。 */
    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
}
