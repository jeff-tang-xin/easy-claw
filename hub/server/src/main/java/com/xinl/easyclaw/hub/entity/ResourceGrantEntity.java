package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 通用资源授权（resource_grants，V30）：资源下放能力的授权唯一实现——
 * (resource_type, resource_id) 定位各类型目录行，user_id + 时效窗口判定可用性。
 * db-connection 是第一个类型；ops-server 存量仍走 ops_server_grants（V18），迁移后并入本表。
 * 一人一资源一条（uk），续期 = 更新 valid_from/valid_until；过期行保留作历史，
 * 下发查询按 now 过滤。org_id 冗余自资源归属，便于按组织鉴权；全库无外键，一致性由应用层保证。
 */
@Getter
@Setter
@Entity
@Table(name = "resource_grants",
        uniqueConstraints = @UniqueConstraint(name = "uk_resource_grant",
                columnNames = {"resource_type", "resource_id", "user_id"}),
        indexes = @Index(name = "idx_resource_grants_user",
                columnList = "resource_type, user_id, valid_until"))
public class ResourceGrantEntity extends BaseEntity {

    /** 资源类型（db-connection / ops-server / ...，与下发端点 {type} 对齐）。 */
    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    /** 各类型目录表 id（无外键，一致性由应用层保证）。 */
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 冗余自资源归属组织（一致性由应用层保证，无外键）。 */
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
