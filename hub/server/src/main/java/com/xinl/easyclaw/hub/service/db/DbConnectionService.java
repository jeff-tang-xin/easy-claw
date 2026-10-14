package com.xinl.easyclaw.hub.service.db;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.db.CreateDbConnectionRequest;
import com.xinl.easyclaw.hub.contract.db.DbConnectionDto;
import com.xinl.easyclaw.hub.contract.db.DbConnectionGrantDto;
import com.xinl.easyclaw.hub.contract.db.UpdateDbConnectionRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeResourceItem;
import com.xinl.easyclaw.hub.entity.DbConnectionEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.ResourceGrantEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.DbConnectionRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.ResourceGrantRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.service.CryptoService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import com.xinl.easyclaw.hub.service.resource.ResourceGrantService;
import com.xinl.easyclaw.hub.service.resource.SpokeResourceCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据库连接目录服务（db_connections，V30）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * 实现 {@link SpokeResourceCatalog}（type=db-connection）接入通用下发端点——
 * 授权/下发/authorize-check 管道由 resource_grants + SpokeResourceController 通用承载，
 * 本类只负责目录 schema 与类型特有校验（dbType 四类枚举、databaseName 必填）。
 * serverKey 全局唯一、创建后不可改；passwordEnc AES-256-GCM 仅下发 spoke，平台永不回显。
 */
@Service
public class DbConnectionService implements SpokeResourceCatalog {

    /** 资源类型标识（= 通用下发端点路径段 + resource_grants.resource_type）。 */
    public static final String RESOURCE_TYPE = "db-connection";

    private static final Set<String> DB_TYPES = Set.of("mysql", "postgresql", "sqlserver", "oracle");

    private final DbConnectionRepository repo;
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final ResourceGrantRepository grants;
    private final ResourceGrantService grantService;
    private final PlatformAdminGuard guard;
    private final AuditService auditService;
    private final CryptoService crypto;
    private final com.xinl.easyclaw.hub.repository.ProjectResourceRepository projectResources;

    public DbConnectionService(DbConnectionRepository repo, OrganizationRepository organizations,
                               ProjectRepository projects, UserRepository users,
                               ResourceGrantRepository grants, ResourceGrantService grantService,
                               PlatformAdminGuard guard, AuditService auditService, CryptoService crypto,
                               com.xinl.easyclaw.hub.repository.ProjectResourceRepository projectResources) {
        this.repo = repo;
        this.organizations = organizations;
        this.projects = projects;
        this.users = users;
        this.grants = grants;
        this.grantService = grantService;
        this.guard = guard;
        this.auditService = auditService;
        this.crypto = crypto;
        this.projectResources = projectResources;
    }

    @Override
    public String type() {
        return RESOURCE_TYPE;
    }

    // ---- 通用下发管道（SpokeResourceCatalog）----

    /**
     * spoke 下发用（GET /api/spoke/resources/db-connection）：仅启用项，按 sort_order,id 保序。
     * 过滤口径（全类型一致）：归属当前 appkey 组织（org_id=0 的未归属遗留行永远不下发）+
     * 可选按项目过滤（仅 solo 工作区请求带 projectId 时生效；db/ops 专属工作区不带 → 不过滤。
     * V31 N:N——资源未绑定任何项目 = 对所有项目可见，绑定了则要求绑定集合包含请求项目）+
     * 当前 appkey 用户存在未过期授权（resource_grants，valid_from <= now <= valid_until）。
     * password_enc 非空时解密为明文随目录下发（临时直连）；未设置则为 null。
     */
    @Override
    public List<SpokeResourceItem> listEnabledForSpoke(AppKeyContext ctx, Long projectId) {
        Instant now = Instant.now();
        // 授权批查：一次取回该用户在本资源类型上的全部有效授权，内存过滤（替代逐行 exists N+1）
        Set<Long> authorizedIds = grants
                .findByResourceTypeAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
                        RESOURCE_TYPE, ctx.userId(), now, now)
                .stream().map(ResourceGrantEntity::getResourceId).collect(Collectors.toSet());
        // 项目绑定批查（V31 N:N）：resourceId → 绑定的项目集合
        List<DbConnectionEntity> enabled = repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc();
        Map<Long, Set<Long>> projectIdsByResource = projectIdsByResource(
                enabled.stream().map(DbConnectionEntity::getId).toList());
        return enabled.stream()
                .filter(e -> ctx.orgId() != null && ctx.orgId().equals(e.getOrgId()))
                .filter(e -> visibleForProject(projectIdsByResource.get(e.getId()), projectId))
                .filter(e -> authorizedIds.contains(e.getId()))
                .map(e -> new SpokeResourceItem(
                        e.getServerKey(), e.getName(), RESOURCE_TYPE,
                        attributes(e, projectIdsByResource.getOrDefault(e.getId(), Set.of())),
                        e.getPasswordEnc() == null || e.getPasswordEnc().isEmpty()
                                ? null : crypto.decrypt(e.getPasswordEnc())))
                .toList();
    }

    /** 批查资源的项目绑定：resourceId → projectId 集合（V31 N:N）。 */
    private Map<Long, Set<Long>> projectIdsByResource(List<Long> resourceIds) {
        if (resourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> out = new LinkedHashMap<>();
        for (var binding : projectResources.findByResourceTypeAndResourceIdIn("db", resourceIds)) {
            out.computeIfAbsent(binding.getResourceId(), k -> new java.util.HashSet<>())
                    .add(binding.getProjectId());
        }
        return out;
    }

    /** 项目过滤只针对 solo 工作区：请求未带项目（db/ops 专属工作区）→ 不过滤；
     * 资源未绑定任何项目 = 与项目无关对所有项目可见；绑定了则要求包含请求项目。 */
    private static boolean visibleForProject(Set<Long> boundProjectIds, Long requestingProjectId) {
        if (boundProjectIds == null || boundProjectIds.isEmpty()) {
            return true;
        }
        return requestingProjectId == null || boundProjectIds.contains(requestingProjectId);
    }

    /** 类型特有字段 → 通用信封 attributes（spoke 侧 SpokeDbConnectionView.from 解析）。 */
    private static Map<String, String> attributes(DbConnectionEntity e, Set<Long> boundProjectIds) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("dbType", e.getDbType());
        attrs.put("host", e.getHost());
        attrs.put("port", String.valueOf(e.getPort()));
        attrs.put("databaseName", e.getDatabaseName());
        attrs.put("username", e.getUsername());
        attrs.put("description", e.getDescription());
        attrs.put("readonlyHint", String.valueOf(Boolean.TRUE.equals(e.getReadonlyHint())));
        attrs.put("projectIds", boundProjectIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")));
        attrs.put("hasPassword", String.valueOf(e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty()));
        return attrs;
    }

    /**
     * spoke 活跃连接授权校验（POST /api/spoke/resources/db-connection/authorize-check）：
     * 对传入 serverKey 判定「归属当前 appkey 组织 + 当前用户存在未过期授权」，
     * 返回 serverKey → 是否仍授权。未知 serverKey 一律 false（撤销/删除后连接必须断开）。
     * serverKey 批查一次完成（{@code findByServerKeyIn}），替代逐 key N+1 查询。
     */
    @Override
    public Map<String, Boolean> authorizeCheck(AppKeyContext ctx, List<String> serverKeys) {
        List<String> distinctKeys = new LinkedHashSet<>(serverKeys).stream()
                .filter(key -> key != null && !key.isBlank())
                .toList();
        Map<String, Long> idByServerKey = new LinkedHashMap<>();
        if (!distinctKeys.isEmpty()) {
            for (DbConnectionEntity e : repo.findByServerKeyIn(distinctKeys)) {
                if (ctx.orgId() != null && ctx.orgId().equals(e.getOrgId())
                        && Boolean.TRUE.equals(e.getEnabled())) {
                    idByServerKey.put(e.getServerKey(), e.getId());
                }
            }
        }
        return grantService.authorizeCheck(
                new ResourceGrantService.AppKeyCheckContext(ctx.userId(), ctx.orgId()),
                RESOURCE_TYPE, idByServerKey);
    }

    // ---- 目录 CRUD（platformAdmin，类型特异不通用化）----

    /** 目录清单（platformAdmin），按 sort_order,id 保序；项目绑定批查（避免逐行 N+1）。 */
    public List<DbConnectionDto> listCatalog(Long requesterId) {
        guard.require(requesterId);
        List<DbConnectionEntity> all = repo.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, Set<Long>> projectIdsByResource = projectIdsByResource(
                all.stream().map(DbConnectionEntity::getId).toList());
        return all.stream()
                .map(e -> toDto(e, projectIdsByResource.getOrDefault(e.getId(), Set.of())))
                .toList();
    }

    /**
     * 新增目录项（platformAdmin）：serverKey 全局唯一（409），留空按名称自动生成；
     * dbType 限四类；orgId 必须为正且组织存在。项目绑定走项目目录页（V31 N:N），不在本接口。
     * databaseName 可空（MySQL/PG/SQLServer 可只连实例）；Oracle 必填（service_name）。
     */
    @Transactional
    public DbConnectionDto createCatalogItem(Long requesterId, CreateDbConnectionRequest req) {
        guard.require(requesterId);
        String serverKey = req.serverKey() == null || req.serverKey().isBlank()
                ? generateServerKey(req.name())
                : req.serverKey().trim();
        if (repo.existsByServerKey(serverKey)) {
            throw ApiException.conflict("serverKey 已存在");
        }
        Long orgId = requireOrgId(req.orgId());
        // dbType 校验与 update 同走 service 层（Bean Validation @Pattern 仍是第一道，
        // 这里是唯一权威口径——两入口一套规则，避免「改了 update 漏了 create」类漂移）
        requireDbType(req.dbType());
        String databaseName = req.databaseName() == null ? "" : req.databaseName().trim();
        requireDatabaseName(req.dbType(), databaseName);
        DbConnectionEntity e = new DbConnectionEntity();
        e.setServerKey(serverKey);
        e.setName(req.name().trim());
        e.setDbType(req.dbType().trim());
        e.setHost(req.host().trim());
        e.setPort(req.port());
        e.setDatabaseName(databaseName);
        e.setUsername(req.username().trim());
        e.setDescription(req.description() == null ? "" : req.description().trim());
        e.setReadonlyHint(req.readonlyHint() == null ? Boolean.FALSE : req.readonlyHint());
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        e.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        e.setOrgId(orgId);
        e.setPasswordEnc(encryptOrNull(req.password()));
        repo.save(e);
        auditService.record(AuditModule.DB, "create_db_connection", requesterId, null, "db_connection",
                String.valueOf(e.getId()), "serverKey=" + serverKey + ",dbType=" + e.getDbType(),
                AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 更新目录项（platformAdmin）：字段全可空 = 不传不改；serverKey 创建后不可改。 */
    @Transactional
    public DbConnectionDto updateCatalogItem(Long requesterId, Long id, UpdateDbConnectionRequest req) {
        guard.require(requesterId);
        DbConnectionEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("数据库连接不存在"));
        if (req.name() != null) {
            e.setName(req.name().trim());
        }
        if (req.dbType() != null) {
            requireDbType(req.dbType());
            e.setDbType(req.dbType().trim());
        }
        if (req.host() != null) {
            e.setHost(req.host().trim());
        }
        if (req.port() != null) {
            e.setPort(req.port());
        }
        if (req.databaseName() != null) {
            e.setDatabaseName(req.databaseName().trim());
        }
        if (req.username() != null) {
            e.setUsername(req.username().trim());
        }
        if (req.description() != null) {
            e.setDescription(req.description().trim());
        }
        if (req.readonlyHint() != null) {
            e.setReadonlyHint(req.readonlyHint());
        }
        if (req.sortOrder() != null) {
            e.setSortOrder(req.sortOrder());
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        if (req.orgId() != null) {
            e.setOrgId(requireOrgId(req.orgId()));
        }
        if (req.password() != null) {
            // null = 保持原密码；非 null = 覆盖（空串 = 清除，spoke 回退当次手输）
            e.setPasswordEnc(encryptOrNull(req.password()));
        }
        // 终态校验：dbType 与 databaseName 的联动约束（如改成 Oracle 但库名为空 = 建连必败）
        requireDatabaseName(e.getDbType(), e.getDatabaseName());
        repo.save(e);
        auditService.record(AuditModule.DB, "update_db_connection", requesterId, null, "db_connection",
                String.valueOf(e.getId()), "serverKey=" + e.getServerKey(), AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 删除目录项（platformAdmin）：连带删除授权行（目录没了授权无意义）。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        guard.require(requesterId);
        DbConnectionEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("数据库连接不存在"));
        List<ResourceGrantEntity> related = grants.findByResourceTypeAndResourceIdOrderByIdAsc(RESOURCE_TYPE, id);
        grants.deleteAll(related);
        repo.delete(e);
        auditService.record(AuditModule.DB, "delete_db_connection", requesterId, null, "db_connection",
                String.valueOf(id), "serverKey=" + e.getServerKey()
                        + ",revokedGrants=" + related.size(), AuditModule.SUCCESS);
    }

    // ---- 授权（platformAdmin，委托通用 ResourceGrantService）----

    /** 授权用户访问连接（platformAdmin）：同 (type,connectionId,userId) 已有授权即续期。 */
    @Transactional
    public DbConnectionGrantDto grant(Long requesterId, Long connectionId,
                                      com.xinl.easyclaw.hub.contract.db.CreateDbConnectionGrantRequest req) {
        guard.require(requesterId);
        DbConnectionEntity connection = repo.findById(connectionId)
                .orElseThrow(() -> ApiException.notFound("数据库连接不存在"));
        ResourceGrantEntity g = grantService.grant(requesterId, RESOURCE_TYPE, connectionId,
                connection.getOrgId(), req.userId(), req.validUntil());
        String userName = grantService.usernames(List.of(g)).get(g.getUserId());
        return toGrantDto(g, userName, now());
    }

    /** 撤销授权（platformAdmin）。 */
    @Transactional
    public void revokeGrant(Long requesterId, Long grantId) {
        guard.require(requesterId);
        grantService.revoke(requesterId, RESOURCE_TYPE, grantId);
    }

    /** 某连接的授权清单（platformAdmin），按 id 保序；userName 回填，expired=valid_until<now。 */
    @Transactional(readOnly = true)
    public List<DbConnectionGrantDto> listGrants(Long requesterId, Long connectionId) {
        guard.require(requesterId);
        repo.findById(connectionId).orElseThrow(() -> ApiException.notFound("数据库连接不存在"));
        List<ResourceGrantEntity> rows = grantService.listGrants(RESOURCE_TYPE, connectionId);
        Map<Long, String> names = grantService.usernames(rows);
        Instant now = now();
        return rows.stream().map(g -> toGrantDto(g, names.get(g.getUserId()), now)).toList();
    }

    private static Instant now() {
        return Instant.now();
    }

    private static DbConnectionGrantDto toGrantDto(ResourceGrantEntity g, String userName, Instant now) {
        return new DbConnectionGrantDto(g.getId(), g.getResourceId(), g.getUserId(), userName,
                g.getValidFrom(), g.getValidUntil(), g.getGrantedBy(), g.getValidUntil().isBefore(now));
    }

    // ---- 校验与工具 ----

    private static void requireDbType(String dbType) {
        if (dbType == null || !DB_TYPES.contains(dbType.trim())) {
            throw ApiException.validation("dbType 仅支持 mysql/postgresql/sqlserver/oracle");
        }
    }

    /**
     * databaseName 按库类型校验：MySQL/PostgreSQL/SQLServer 可空（只连实例，微服务多库场景
     * 查询时用 schema.table 限定名）；Oracle 必填——service_name 是 JDBC 建连必需品，空了连不上。
     */
    private static void requireDatabaseName(String dbType, String databaseName) {
        if ("oracle".equals(dbType) && (databaseName == null || databaseName.isBlank())) {
            throw ApiException.validation("Oracle 连接必须填写 service_name（数据库名）");
        }
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

    /** projectId 校验已随 V31 N:N 移除：绑定走项目目录页，资源服务不再持有项目归属。 */

    /**
     * serverKey 自动生成（同 ops 口径）：名称转小写、非 [a-z0-9_-] 折叠为 '-' 并去首尾 '-'；
     * 结果为空（如纯中文名）回退 "db"；与存量冲突时追加 -2/-3… 保证唯一。
     */
    private String generateServerKey(String name) {
        String base = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "db";
        }
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

    private DbConnectionDto toDto(DbConnectionEntity e) {
        return toDto(e, Set.copyOf(boundProjectIds(e.getId())));
    }

    private DbConnectionDto toDto(DbConnectionEntity e, Set<Long> projectIds) {
        return new DbConnectionDto(e.getId(), e.getServerKey(), e.getName(), e.getDbType(),
                e.getHost(), e.getPort(), e.getDatabaseName(), e.getUsername(), e.getDescription(),
                e.getReadonlyHint(), e.getSortOrder(), e.getEnabled(), e.getOrgId(),
                projectIds.stream().sorted().toList(),
                e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty());
    }

    /** 资源绑定的项目 id 集合（V31 N:N；升序，空 = 未绑定任何项目）。 */
    private List<Long> boundProjectIds(Long resourceId) {
        return projectResources.findByResourceTypeAndResourceIdIn("db", List.of(resourceId)).stream()
                .map(com.xinl.easyclaw.hub.entity.ProjectResourceEntity::getProjectId)
                .sorted()
                .toList();
    }

    /** 明文 → 密文；null/空白输入返回 null（不落库）。 */
    private String encryptOrNull(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return crypto.encrypt(plaintext);
    }
}
