package com.xinl.easyclaw.hub.service.resource;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.entity.ResourceGrantEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.ResourceGrantRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.service.AuditService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用资源授权服务（resource_grants，V30）：资源下放能力的授权唯一实现。
 * (resourceType, resourceId) 定位各类型目录行；授权判定与资源类型无关——
 * 「归属组织 + 用户存在未过期授权」的口径全类型一致（同 OpsServerService V18 语义）。
 * 各类型目录服务持有本服务完成 grant/revoke/check；本服务不感知具体目录表。
 */
@Service
public class ResourceGrantService {

    private final ResourceGrantRepository repo;
    private final UserRepository users;
    private final AuditService auditService;

    public ResourceGrantService(ResourceGrantRepository repo, UserRepository users,
                                AuditService auditService) {
        this.repo = repo;
        this.users = users;
        this.auditService = auditService;
    }

    /**
     * 授权用户访问资源（platformAdmin）：同 (type, resourceId, userId) 已有授权即续期
     * （valid_from/valid_until/granted_by 整体刷新，同 ops 口径）。
     *
     * @param resourceOrgId 资源归属组织（冗余落授权行，供按组织鉴权）
     */
    @Transactional
    public ResourceGrantEntity grant(Long requesterId, String resourceType, Long resourceId,
                                     Long resourceOrgId, Long userId, Instant validUntil) {
        if (validUntil == null || !validUntil.isAfter(Instant.now())) {
            throw ApiException.validation("validUntil 必须是未来时刻");
        }
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        ResourceGrantEntity g = repo
                .findByResourceTypeAndResourceIdAndUserId(resourceType, resourceId, userId)
                .orElseGet(ResourceGrantEntity::new);
        boolean renew = g.getId() != null;
        g.setResourceType(resourceType);
        g.setResourceId(resourceId);
        g.setUserId(userId);
        g.setOrgId(resourceOrgId);
        g.setGrantedBy(requesterId);
        g.setValidFrom(Instant.now());
        g.setValidUntil(validUntil);
        repo.save(g);
        auditService.record(AuditModule.DB, renew ? "renew_resource_grant" : "grant_resource_access",
                requesterId, null, "resource_grant", String.valueOf(g.getId()),
                "type=" + resourceType + ",resourceId=" + resourceId + ",userId=" + userId,
                AuditModule.SUCCESS);
        return g;
    }

    /** 撤销授权（platformAdmin）：行删除（与过期保留口径不同——撤销是显式管理动作）。 */
    @Transactional
    public void revoke(Long requesterId, String resourceType, Long grantId) {
        ResourceGrantEntity g = repo.findById(grantId)
                .filter(x -> x.getResourceType().equals(resourceType))
                .orElseThrow(() -> ApiException.notFound("授权不存在"));
        repo.delete(g);
        auditService.record(AuditModule.DB, "revoke_resource_grant", requesterId, null,
                "resource_grant", String.valueOf(grantId),
                "type=" + resourceType + ",resourceId=" + g.getResourceId()
                        + ",userId=" + g.getUserId(),
                AuditModule.SUCCESS);
    }

    /** 某资源的授权清单（platformAdmin），按 id 保序；userName 回填，expired = valid_until < now。 */
    @Transactional(readOnly = true)
    public List<ResourceGrantEntity> listGrants(String resourceType, Long resourceId) {
        return repo.findByResourceTypeAndResourceIdOrderByIdAsc(resourceType, resourceId);
    }

    /** 批量回填用户名（授权清单展示用）。 */
    @Transactional(readOnly = true)
    public Map<Long, String> usernames(List<ResourceGrantEntity> grants) {
        if (grants.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(grants.stream().map(ResourceGrantEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, UserEntity::getUsername));
    }

    /**
     * 活跃连接授权校验（spoke 定时批量调用）：对传入 serverKey → resourceId 映射逐个判定
     * 「归属当前 appkey 组织 + 当前用户存在未过期授权」；未知 key 一律 false（撤销/删除后连接必须断开）。
     */
    public Map<String, Boolean> authorizeCheck(AppKeyCheckContext ctx, String resourceType,
                                               Map<String, Long> idByServerKey) {
        Instant now = Instant.now();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : new LinkedHashSet<>(idByServerKey.keySet())) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Long resourceId = idByServerKey.get(key);
            out.put(key, resourceId != null && repo
                    .existsByResourceTypeAndResourceIdAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
                            resourceType, resourceId, ctx.userId(), now, now));
        }
        return out;
    }

    /** 授权校验上下文（解耦 AppKeyContext，避免 service.resource 依赖 security 包以外细节）。 */
    public record AppKeyCheckContext(Long userId, Long orgId) {
    }
}
