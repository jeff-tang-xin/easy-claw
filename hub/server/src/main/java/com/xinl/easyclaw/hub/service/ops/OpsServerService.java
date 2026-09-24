package com.xinl.easyclaw.hub.service.ops;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerGrantRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.ops.OpsServerDto;
import com.xinl.easyclaw.hub.contract.ops.OpsServerGrantDto;
import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsServer;
import com.xinl.easyclaw.hub.entity.OpsServerEntity;
import com.xinl.easyclaw.hub.entity.OpsServerGrantEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.OpsServerGrantRepository;
import com.xinl.easyclaw.hub.repository.OpsServerRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.service.CryptoService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运维服务器目录服务（ops_servers，V17）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * serverKey 全局唯一、创建后不可改（spoke 侧稳定标识）。
 * V18 起服务器归属组织+项目（org_id/project_id，0=未归属遗留行），spoke 下发按
 * appkey 组织 + 用户时效授权（ops_server_grants）过滤。
 * V19 起可存运维登录密码密文（CryptoService，AES-256-GCM），下发 spoke 时解密为明文
 * 供临时运维直连；平台回显只出 passwordSet 布尔位。
 */
@Service
public class OpsServerService {

    private final OpsServerRepository repo;
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final OpsServerGrantRepository grants;
    private final PlatformAdminGuard guard;
    private final AuditService auditService;
    private final CryptoService crypto;

    public OpsServerService(OpsServerRepository repo, OrganizationRepository organizations,
                            ProjectRepository projects, UserRepository users,
                            OpsServerGrantRepository grants, PlatformAdminGuard guard,
                            AuditService auditService, CryptoService crypto) {
        this.repo = repo;
        this.organizations = organizations;
        this.projects = projects;
        this.users = users;
        this.grants = grants;
        this.guard = guard;
        this.auditService = auditService;
        this.crypto = crypto;
    }

    /** 目录清单（platformAdmin），按 sort_order,id 保序。 */
    public List<OpsServerDto> listCatalog(Long requesterId) {
        guard.require(requesterId);
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(OpsServerService::toDto).toList();
    }

    /**
     * 新增目录项（platformAdmin）：serverKey 全局唯一（409），port 缺省 22，可空文本归一化为空串。
     * serverKey 留空时按名称自动生成（前端「留空则按名称自动生成」承诺的语义）；
     * orgId 必须为正且组织存在（组织必须）；projectId 可空（0 = 不限定项目，可选标记）。
     */
    @Transactional
    public OpsServerDto createCatalogItem(Long requesterId, CreateOpsServerRequest req) {
        guard.require(requesterId);
        String serverKey = req.serverKey() == null || req.serverKey().isBlank()
                ? generateServerKey(req.name())
                : req.serverKey().trim();
        if (repo.existsByServerKey(serverKey)) {
            throw ApiException.conflict("serverKey 已存在");
        }
        Long orgId = requireOrgId(req.orgId());
        Long projectId = requireProjectId(orgId, req.projectId());
        OpsServerEntity e = new OpsServerEntity();
        e.setServerKey(serverKey);
        e.setName(req.name().trim());
        e.setHost(req.host().trim());
        e.setPort(req.port() == null ? 22 : req.port());
        e.setUsername(req.username() == null ? "" : req.username().trim());
        e.setDescription(req.description() == null ? "" : req.description().trim());
        e.setOsType(req.osType() == null ? "" : req.osType().trim());
        e.setCategory(req.category().trim());
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        e.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        e.setOrgId(orgId);
        e.setProjectId(projectId);
        e.setPasswordEnc(encryptOrNull(req.password()));
        repo.save(e);
        auditService.record(AuditModule.OPS, "create_ops_server", requesterId, null, "ops_server",
                String.valueOf(e.getId()), "serverKey=" + serverKey, AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 更新目录项（platformAdmin）：字段全可空 = 不传不改；serverKey 创建后不可改（请求体不含该字段）。 */
    @Transactional
    public OpsServerDto updateCatalogItem(Long requesterId, Long id, UpdateOpsServerRequest req) {
        guard.require(requesterId);
        OpsServerEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("运维服务器不存在"));
        if (req.name() != null) {
            e.setName(req.name().trim());
        }
        if (req.host() != null) {
            e.setHost(req.host().trim());
        }
        if (req.port() != null) {
            e.setPort(req.port());
        }
        if (req.username() != null) {
            e.setUsername(req.username().trim());
        }
        if (req.description() != null) {
            e.setDescription(req.description().trim());
        }
        if (req.osType() != null) {
            e.setOsType(req.osType().trim());
        }
        if (req.category() != null) {
            e.setCategory(req.category().trim());
        }
        if (req.sortOrder() != null) {
            e.setSortOrder(req.sortOrder());
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        if (req.orgId() != null || req.projectId() != null) {
            Long orgId = req.orgId() != null ? requireOrgId(req.orgId()) : e.getOrgId();
            if (req.projectId() != null) {
                e.setProjectId(requireProjectId(orgId, req.projectId()));
            } else if (req.orgId() != null && !req.orgId().equals(e.getOrgId())
                    && e.getProjectId() != null && e.getProjectId() > 0) {
                // 旧项目属于旧组织，换组织必须连带指定新项目，否则留下 project ∉ org 的脏数据
                throw ApiException.validation("更换归属组织时须同时指定 projectId");
            }
            e.setOrgId(orgId);
        }
        if (req.password() != null) {
            // null = 保持原密码；非 null = 覆盖（空串 = 清除，spoke 回退本地录入凭证）
            e.setPasswordEnc(encryptOrNull(req.password()));
        }
        repo.save(e);
        auditService.record(AuditModule.OPS, "update_ops_server", requesterId, null, "ops_server",
                String.valueOf(e.getId()), "serverKey=" + e.getServerKey(), AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 删除目录项（platformAdmin）。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        guard.require(requesterId);
        OpsServerEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("运维服务器不存在"));
        repo.delete(e);
        auditService.record(AuditModule.OPS, "delete_ops_server", requesterId, null, "ops_server",
                String.valueOf(id), "serverKey=" + e.getServerKey(), AuditModule.SUCCESS);
    }

    /**
     * spoke 下发用（GET /api/spoke/ops-servers）：仅启用项，按 sort_order,id 保序。
     * V19：password_enc 非空时解密为明文随目录下发（临时运维直连）；未设置则为 null。
     * V18 过滤口径：归属当前 appkey 组织（org_id=0 的未归属遗留行永远不下发）+
     * 可选按绑定项目过滤（projectId 非空时）+ 当前 appkey 用户存在未过期授权
     * （valid_from &lt;= now &lt;= valid_until，一人一服务器一行，过期即不可见）。
     */
    public List<SpokeOpsServer> listEnabledForSpoke(AppKeyContext ctx, Long projectId) {
        Instant now = Instant.now();
        return repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .filter(e -> ctx.orgId().equals(e.getOrgId()))
                // 项目为可选标记：projectId<=0（不限定项目）对所有项目可见；>0 时按项目过滤
                .filter(e -> projectId == null || e.getProjectId() == null || e.getProjectId() <= 0
                        || projectId.equals(e.getProjectId()))
                .filter(e -> grants.existsByServerIdAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
                        e.getId(), ctx.userId(), now, now))
                .map(e -> new SpokeOpsServer(e.getServerKey(), e.getName(), e.getHost(), e.getPort(),
                        e.getUsername(), e.getDescription(), e.getOsType(), e.getProjectId(),
                        e.getPasswordEnc() == null || e.getPasswordEnc().isEmpty()
                                ? null : crypto.decrypt(e.getPasswordEnc())))
                .toList();
    }

    /**
     * spoke 活跃连接授权校验（POST /api/spoke/ops-servers/authorize-check）：
     * 对传入 serverKey 逐个判定「归属当前 appkey 组织 + 当前用户存在未过期授权」，
     * 返回 serverKey → 是否仍授权。未知 serverKey 一律 false（撤销/删除后连接必须断开）。
     */
    public Map<String, Boolean> authorizeCheck(AppKeyContext ctx, List<String> serverKeys) {
        Instant now = Instant.now();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : new LinkedHashSet<>(serverKeys)) {
            if (key == null || key.isBlank()) {
                continue;
            }
            out.put(key, repo.findByServerKey(key)
                    .filter(e -> ctx.orgId().equals(e.getOrgId()))
                    .filter(e -> Boolean.TRUE.equals(e.getEnabled()))
                    .filter(e -> grants.existsByServerIdAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
                            e.getId(), ctx.userId(), now, now))
                    .isPresent());
        }
        return out;
    }

    /** 授权用户访问服务器（platformAdmin）：同 (serverId,userId) 已有授权即续期（valid_from/valid_until/granted_by 整体刷新）。 */
    @Transactional
    public OpsServerGrantDto grant(Long requesterId, Long serverId, CreateOpsServerGrantRequest req) {
        guard.require(requesterId);
        OpsServerEntity server = repo.findById(serverId)
                .orElseThrow(() -> ApiException.notFound("运维服务器不存在"));
        Instant now = Instant.now();
        if (req.validUntil() == null || !req.validUntil().isAfter(now)) {
            throw ApiException.validation("validUntil 必须是未来时刻");
        }
        UserEntity user = users.findById(req.userId()).orElseThrow(() -> ApiException.notFound("用户不存在"));
        OpsServerGrantEntity g = grants.findByServerIdAndUserId(serverId, req.userId())
                .orElseGet(OpsServerGrantEntity::new);
        boolean renew = g.getId() != null;
        g.setServerId(serverId);
        g.setUserId(req.userId());
        g.setOrgId(server.getOrgId());
        g.setGrantedBy(requesterId);
        g.setValidFrom(now);
        g.setValidUntil(req.validUntil());
        grants.save(g);
        auditService.record(AuditModule.OPS, renew ? "renew_ops_server_grant" : "grant_ops_server_access",
                requesterId, null, "ops_server_grant", String.valueOf(g.getId()),
                "serverKey=" + server.getServerKey() + ",userId=" + req.userId(), AuditModule.SUCCESS);
        return toGrantDto(g, user.getUsername(), now);
    }

    /** 撤销授权（platformAdmin）：行删除（与过期保留口径不同——撤销是显式管理动作）。 */
    @Transactional
    public void revokeGrant(Long requesterId, Long grantId) {
        guard.require(requesterId);
        OpsServerGrantEntity g = grants.findById(grantId).orElseThrow(() -> ApiException.notFound("授权不存在"));
        grants.delete(g);
        auditService.record(AuditModule.OPS, "revoke_ops_server_grant", requesterId, null, "ops_server_grant",
                String.valueOf(grantId), "serverId=" + g.getServerId() + ",userId=" + g.getUserId(),
                AuditModule.SUCCESS);
    }

    /** 某服务器的授权清单（platformAdmin），按 id 保序；userName 查 users 表回填，expired=valid_until&lt;now。 */
    @Transactional(readOnly = true)
    public List<OpsServerGrantDto> listGrants(Long requesterId, Long serverId) {
        guard.require(requesterId);
        repo.findById(serverId).orElseThrow(() -> ApiException.notFound("运维服务器不存在"));
        List<OpsServerGrantEntity> rows = grants.findByServerIdOrderByIdAsc(serverId);
        Map<Long, String> names = rows.isEmpty() ? Map.of()
                : users.findAllById(rows.stream().map(OpsServerGrantEntity::getUserId).distinct().toList())
                        .stream().collect(Collectors.toMap(UserEntity::getId, UserEntity::getUsername));
        Instant now = Instant.now();
        return rows.stream().map(g -> toGrantDto(g, names.get(g.getUserId()), now)).toList();
    }

    private static OpsServerGrantDto toGrantDto(OpsServerGrantEntity g, String userName, Instant now) {
        return new OpsServerGrantDto(g.getId(), g.getServerId(), g.getUserId(), userName,
                g.getValidFrom(), g.getValidUntil(), g.getGrantedBy(), g.getValidUntil().isBefore(now));
    }

    /** orgId 必须为正整数且组织存在（0/负数=入参错误 400，不存在=404）。 */
    private Long requireOrgId(Long orgId) {
        if (orgId == null || orgId <= 0) {
            throw ApiException.validation("orgId 必须为正整数");
        }
        if (!organizations.existsById(orgId)) {
            throw ApiException.notFound("组织不存在");
        }
        return orgId;
    }

    /** projectId 可选标记：0/null = 不限定项目（合法）；>0 时必须存在且属于该组织（全库无外键，归属一致性在此收口）。 */
    private Long requireProjectId(Long orgId, Long projectId) {
        if (projectId == null || projectId <= 0) {
            return 0L;
        }
        ProjectEntity p = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (!orgId.equals(p.getOrgId())) {
            throw ApiException.validation("项目不属于该组织");
        }
        return projectId;
    }

    /**
     * serverKey 自动生成（前端「留空则按名称自动生成」承诺的语义）：名称转小写、非 [a-z0-9_-] 折叠为 '-'
     * 并去首尾 '-'；结果为空（如纯中文名）回退 "server"；与存量冲突时追加 -2/-3… 保证 uk_ops_server_key 唯一。
     */
    private String generateServerKey(String name) {
        String base = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "server";
        }
        // 截断留出冲突后缀空间（DB 列 64；name 最长 128）
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        String key = base;
        int n = 2;
        while (repo.existsByServerKey(key)) {
            key = base + "-" + n++;
        }
        return key;
    }

    private static OpsServerDto toDto(OpsServerEntity e) {
        return new OpsServerDto(e.getId(), e.getServerKey(), e.getName(), e.getHost(), e.getPort(),
                e.getUsername(), e.getDescription(), e.getOsType(), e.getCategory(), e.getSortOrder(),
                e.getEnabled(), e.getOrgId(), e.getProjectId(),
                e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty());
    }

    /** 明文 → 密文；null/空白输入返回 null（不落库）。 */
    private String encryptOrNull(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return crypto.encrypt(plaintext);
    }
}
