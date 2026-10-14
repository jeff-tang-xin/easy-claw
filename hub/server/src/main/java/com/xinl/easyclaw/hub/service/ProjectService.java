package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.project.BindProjectResourcesRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.project.ProjectDto;
import com.xinl.easyclaw.hub.contract.project.UpdateProjectRequest;
import com.xinl.easyclaw.hub.common.Slugger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.ProjectResourceEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import com.xinl.easyclaw.hub.entity.DbConnectionEntity;
import com.xinl.easyclaw.hub.entity.OpsServerEntity;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.ProjectResourceRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.repository.WorkspaceRepository;
import com.xinl.easyclaw.hub.repository.DbConnectionRepository;
import com.xinl.easyclaw.hub.repository.OpsServerRepository;

/**
 * 项目管理：组织内归类锚点。可见性 private|team|public，按角色 + 归属过滤；归档=status:archived。
 * 项目的新建/修改/归档记审计。V31 起承担资源绑定收口（project_resources N:N，整组覆盖）。
 */
@Service
public class ProjectService {

    private static final Set<String> VALID_VISIBILITY = Set.of("private", "team", "public");
    private static final Set<String> VALID_STATUS = Set.of("active", "archived");

    /** project_resources.resource_type 取值（与 V31 迁移脚本、各资源服务批查口径一致）。 */
    public static final String RESOURCE_TYPE_DB = "db";
    public static final String RESOURCE_TYPE_OPS = "ops";

    private final ProjectRepository projects;
    private final UserRepository users;
    private final OrgService orgService;
    private final AuditService auditService;
    private final WorkspaceRepository workspaces;
    private final DbConnectionRepository dbConnections;
    private final OpsServerRepository opsServers;
    private final ProjectResourceRepository projectResources;

    public ProjectService(ProjectRepository projects, UserRepository users, OrgService orgService,
                          AuditService auditService, WorkspaceRepository workspaces,
                          DbConnectionRepository dbConnections, OpsServerRepository opsServers,
                          ProjectResourceRepository projectResources) {
        this.projects = projects;
        this.users = users;
        this.orgService = orgService;
        this.auditService = auditService;
        this.workspaces = workspaces;
        this.dbConnections = dbConnections;
        this.opsServers = opsServers;
        this.projectResources = projectResources;
    }

    /** 组织下项目列表：owner/admin 全量；member/guest 见 team+public+自己拥有的 private。 */
    public List<ProjectDto> list(Long requesterId, Long orgId) {
        String role = orgService.roleOf(orgId, requesterId);
        if (role == null) {
            throw ApiException.forbidden("非组织成员");
        }
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        List<ProjectEntity> visible = new ArrayList<>();
        Set<Long> ownerIds = new HashSet<>();
        for (ProjectEntity p : projects.findByOrgId(orgId)) {
            if (canSee(p, requesterId, privileged)) {
                visible.add(p);
                ownerIds.add(p.getOwnerUserId());
            }
        }
        Map<Long, String> names = resolveUsernames(ownerIds);
        return visible.stream().map(p -> toDto(p, names)).toList();
    }

    public ProjectDto get(Long requesterId, Long projectId) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        // 项目 owner 始终可读（即使不在项目所属组织——跨组织迁移后新 owner 可能不在目标组织）。
        boolean member = role != null || p.getOwnerUserId().equals(requesterId);
        // public 项目组织外可读；其余需成员（含 owner）且满足可见性。
        if (!"public".equals(p.getVisibility()) && !member) {
            throw ApiException.forbidden("非组织成员");
        }
        if (member && !canSee(p, requesterId, privileged)) {
            throw ApiException.forbidden("无权限查看该项目");
        }
        return toDto(p, resolveUsernames(Set.of(p.getOwnerUserId())));
    }

    /** 建项目：guest 只读不可建；slug 组织内唯一。 */
    @Transactional
    public ProjectDto create(Long requesterId, CreateProjectRequest req) {
        Long orgId = req.orgId();
        String role = orgService.roleOf(orgId, requesterId);
        if (role == null) {
            throw ApiException.forbidden("非组织成员");
        }
        if ("guest".equals(role)) {
            throw ApiException.forbidden("guest 只读，不能创建项目");
        }
        String slug = resolveProjectSlug(orgId, req.name(), req.slug());
        ProjectEntity p = new ProjectEntity();
        p.setOrgId(orgId);
        p.setSlug(slug);
        p.setName(req.name());
        p.setDescription(req.description());
        String visibility = req.visibility() == null || req.visibility().isBlank() ? "team" : req.visibility();
        validateVisibility(visibility);
        p.setVisibility(visibility);
        p.setOwnerUserId(requesterId);
        projects.save(p);
        auditService.record(AuditModule.PROJECT, "create_project", requesterId, orgId, "project", String.valueOf(p.getId()),
                "name=" + p.getName() + ",slug=" + p.getSlug() + ",visibility=" + visibility, AuditModule.SUCCESS);
        return toDto(p, resolveUsernames(Set.of(p.getOwnerUserId())));
    }

    /** 改项目：owner/admin 或项目创建者。status 可用于归档。 */
    @Transactional
    public ProjectDto update(Long requesterId, Long projectId, UpdateProjectRequest req) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        requireCanEdit(p, requesterId);
        StringBuilder changed = new StringBuilder();
        if (req.name() != null) {
            p.setName(req.name());
            changed.append("name,");
        }
        if (req.description() != null) {
            p.setDescription(req.description());
            changed.append("description,");
        }
        if (req.visibility() != null) {
            validateVisibility(req.visibility());
            p.setVisibility(req.visibility());
            changed.append("visibility,");
        }
        if (req.status() != null) {
            validateStatus(req.status());
            p.setStatus(req.status());
            changed.append("status,");
        }
        projects.save(p);
        String detail = changed.length() == 0 ? "no-change"
                : "fields=" + changed.substring(0, changed.length() - 1);
        auditService.record(AuditModule.PROJECT, "update_project", requesterId, p.getOrgId(), "project",
                String.valueOf(p.getId()), detail, AuditModule.SUCCESS);
        return toDto(p, resolveUsernames(Set.of(p.getOwnerUserId())));
    }

    /** 归档（软删）：owner/admin 或项目创建者。 */
    @Transactional
    public void archive(Long requesterId, Long projectId) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        requireCanEdit(p, requesterId);
        p.setStatus("archived");
        projects.save(p);
        auditService.record(AuditModule.PROJECT, "archive_project", requesterId, p.getOrgId(), "project",
                String.valueOf(p.getId()), null, AuditModule.SUCCESS);
    }

    /** 恢复归档项目：owner/admin 或项目创建者；仅 archived 状态可恢复。 */
    @Transactional
    public void restore(Long requesterId, Long projectId) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        requireCanEdit(p, requesterId);
        if (!"archived".equals(p.getStatus())) {
            throw ApiException.validation("项目未处于归档状态");
        }
        p.setStatus("active");
        projects.save(p);
        auditService.record(AuditModule.PROJECT, "restore_project", requesterId, p.getOrgId(), "project",
                String.valueOf(p.getId()), null, AuditModule.SUCCESS);
    }

    /**
     * 迁移项目到目标组织（跨组织）。
     * <p>
     * <b>权限</b>：源组织 <b>owner/admin</b> 或<b>平台管理员</b>可发起（member/guest 均不可）；
     * 目标组织可为任意组织，不要求发起人是目标组织成员——owner/admin 是组织级管理角色，
     * 平台管理员是平台级管理角色，均可跨组织迁移。
     * <p>
     * <b>冲突</b>：目标组织内已存在同名 slug 时<b>禁止迁移</b>（不自动改名，返回 409）。
     * <p>
     * <b>级联</b>：项目 org_id 改为目标组织，owner_user_id 改为执行迁移者；
     * 挂 project_id 的资源（workspaces / db_connections / ops_servers）的 org_id 冗余字段
     * 一并同步为目标组织，保证「按组织列举与鉴权」的一致性（这些表无外键，一致性由应用层保证）。
     * 仅挂 project_id 无 org_id 冗余的表（knowledge_items / blackboard_entries / 事件表）
     * 无需改动——它们只按 project_id 归类，project_id 未变。
     *
     * @param requesterId 执行迁移者（迁移后成为项目新 owner）
     * @param projectId   被迁移项目
     * @param targetOrgId 目标组织
     * @return 迁移后的项目视图
     */
    @Transactional
    public ProjectDto transfer(Long requesterId, Long projectId, Long targetOrgId) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        Long sourceOrgId = p.getOrgId();
        if (sourceOrgId.equals(targetOrgId)) {
            throw ApiException.validation("目标组织与当前组织相同，无需迁移");
        }
        // 仅源组织 owner/admin 或平台管理员可迁移（member/guest 均不可）
        String sourceRole = orgService.roleOf(sourceOrgId, requesterId);
        boolean privileged = "owner".equals(sourceRole) || "admin".equals(sourceRole)
                || isPlatformAdmin(requesterId);
        if (!privileged) {
            throw ApiException.forbidden("仅源组织 owner/admin 或平台管理员可迁移项目");
        }
        // 目标组织必须存在
        if (!orgService.orgExists(targetOrgId)) {
            throw ApiException.notFound("目标组织不存在");
        }
        // 冲突禁止迁移：目标组织内已存在同名 slug
        if (projects.existsByOrgIdAndSlug(targetOrgId, p.getSlug())) {
            throw ApiException.conflict("目标组织已存在同名项目 slug：" + p.getSlug());
        }

        // 迁移项目本身
        p.setOrgId(targetOrgId);
        p.setOwnerUserId(requesterId);
        projects.save(p);

        // 级联同步 org_id 冗余字段（仅挂 project_id 且冗余 org_id 的表）
        workspaces.findByProjectId(projectId).ifPresent(w -> {
            w.setOrgId(targetOrgId);
            workspaces.save(w);
        });
        // V31 N:N：经 project_resources 绑定的资源同样跟随迁移（org_id 冗余同步；
        // 资源可能同时绑定同组织其他项目，迁移后绑定关系保留、org_id 以最后迁移为准）
        for (ProjectResourceEntity b : projectResources.findByProjectId(projectId)) {
            if (RESOURCE_TYPE_DB.equals(b.getResourceType())) {
                dbConnections.findById(b.getResourceId()).ifPresent(c -> {
                    c.setOrgId(targetOrgId);
                    dbConnections.save(c);
                });
            } else if (RESOURCE_TYPE_OPS.equals(b.getResourceType())) {
                opsServers.findById(b.getResourceId()).ifPresent(s -> {
                    s.setOrgId(targetOrgId);
                    opsServers.save(s);
                });
            }
        }

        auditService.record(AuditModule.PROJECT, "transfer_project", requesterId, targetOrgId, "project",
                String.valueOf(p.getId()),
                "fromOrg=" + sourceOrgId + ",toOrg=" + targetOrgId + ",slug=" + p.getSlug(),
                AuditModule.SUCCESS);
        return toDto(p, resolveUsernames(Set.of(p.getOwnerUserId())));
    }

    /**
     * 项目资源绑定（V31 N:N）：整组覆盖该项目绑定的数据库连接与运维服务器。
     * <p>
     * <b>权限</b>：owner/admin 或项目创建者（同 update 口径）。
     * <b>校验</b>：资源必须存在且归属项目所在组织（跨组织绑定拒绝——下发按组织过滤，
     * 跨组织绑定永远不可见，属配置错误）。
     * <b>语义</b>：请求里的两个清单即该项目的最终绑定全集（未列出的既有绑定被解除；
     * 空清单 = 清空该类绑定）。资源未绑定任何项目 = 对所有项目可见（下发口径）。
     */
    @Transactional
    public ProjectDto bindResources(Long requesterId, Long projectId, BindProjectResourcesRequest req) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        requireCanEdit(p, requesterId);
        List<String> dbKeys = req.dbServerKeys() == null ? List.of() : req.dbServerKeys();
        List<String> opsKeys = req.opsServerKeys() == null ? List.of() : req.opsServerKeys();

        // 校验：存在 + 同组织（serverKey 去重保序，重复 key 幂等）
        List<Long> dbIds = new ArrayList<>();
        for (String key : new LinkedHashSet<>(dbKeys)) {
            DbConnectionEntity c = dbConnections.findByServerKey(key)
                    .orElseThrow(() -> ApiException.notFound("数据库连接不存在：" + key));
            if (!p.getOrgId().equals(c.getOrgId())) {
                throw ApiException.validation("数据库连接不属于该项目所在组织：" + key);
            }
            dbIds.add(c.getId());
        }
        List<Long> opsIds = new ArrayList<>();
        for (String key : new LinkedHashSet<>(opsKeys)) {
            OpsServerEntity s = opsServers.findByServerKey(key)
                    .orElseThrow(() -> ApiException.notFound("运维服务器不存在：" + key));
            if (!p.getOrgId().equals(s.getOrgId())) {
                throw ApiException.validation("运维服务器不属于该项目所在组织：" + key);
            }
            opsIds.add(s.getId());
        }

        // 整组覆盖：先清后写（唯一约束 uk_project_resource 兜底幂等）
        projectResources.deleteByProjectAndType(projectId, RESOURCE_TYPE_DB);
        projectResources.deleteByProjectAndType(projectId, RESOURCE_TYPE_OPS);
        for (Long id : dbIds) {
            projectResources.save(newBinding(projectId, RESOURCE_TYPE_DB, id));
        }
        for (Long id : opsIds) {
            projectResources.save(newBinding(projectId, RESOURCE_TYPE_OPS, id));
        }
        auditService.record(AuditModule.PROJECT, "bind_project_resources", requesterId, p.getOrgId(),
                "project", String.valueOf(projectId),
                "db=" + dbIds.size() + ",ops=" + opsIds.size(), AuditModule.SUCCESS);
        return toDto(p, resolveUsernames(Set.of(p.getOwnerUserId())));
    }

    private static ProjectResourceEntity newBinding(Long projectId, String type, Long resourceId) {
        ProjectResourceEntity b = new ProjectResourceEntity();
        b.setProjectId(projectId);
        b.setResourceType(type);
        b.setResourceId(resourceId);
        return b;
    }

    /** 显式 slug 校验组织内唯一；留空则从名称派生（纯中文名兜底 "proj"），重名自动追加 -2/-3…。 */
    private String resolveProjectSlug(Long orgId, String name, String requested) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.trim();
            if (Slugger.isValid(slug)) {
                throw ApiException.validation("slug 只能包含小写字母/数字/连字符");
            }
            if (projects.existsByOrgIdAndSlug(orgId, slug)) {
                throw ApiException.conflict("项目 slug 在该组织已存在");
            }
            return slug;
        }
        String base = Slugger.derive(name, "proj");
        String candidate = base;
        for (int i = 2; projects.existsByOrgIdAndSlug(orgId, candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private void requireCanEdit(ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean canEdit = "owner".equals(role) || "admin".equals(role) || p.getOwnerUserId().equals(requesterId);
        if (!canEdit) {
            throw ApiException.forbidden("无权限修改该项目");
        }
    }

    /** 平台管理员：平台级管理角色，可跨组织管理（参照 ProviderService 同款判定）。 */
    private boolean isPlatformAdmin(Long userId) {
        return users.findById(userId).map(UserEntity::isPlatformAdmin).orElse(false);
    }

    /** 可见性判定：特权（owner/admin）全见；team/public 成员可见；private 仅创建者。供工作区等按 project 框定可见性的模块复用。 */
    public static boolean canSee(ProjectEntity p, Long requesterId, boolean privileged) {
        return privileged
                || "team".equals(p.getVisibility())
                || "public".equals(p.getVisibility())
                || p.getOwnerUserId().equals(requesterId);
    }

    private static void validateVisibility(String visibility) {
        if (!VALID_VISIBILITY.contains(visibility)) {
            throw ApiException.validation("非法可见性：" + visibility);
        }
    }

    private static void validateStatus(String status) {
        if (!VALID_STATUS.contains(status)) {
            throw ApiException.validation("非法状态：" + status);
        }
    }

    private ProjectDto toDto(ProjectEntity p, Map<Long, String> names) {
        // V31 N:N：绑定的资源按 serverKey 回填（单项目两次小查询；项目数少，不做批查优化）
        List<String> dbKeys = new ArrayList<>();
        List<String> opsKeys = new ArrayList<>();
        for (ProjectResourceEntity b : projectResources.findByProjectId(p.getId())) {
            if (RESOURCE_TYPE_DB.equals(b.getResourceType())) {
                dbConnections.findById(b.getResourceId()).map(DbConnectionEntity::getServerKey).ifPresent(dbKeys::add);
            } else if (RESOURCE_TYPE_OPS.equals(b.getResourceType())) {
                opsServers.findById(b.getResourceId()).map(OpsServerEntity::getServerKey).ifPresent(opsKeys::add);
            }
        }
        return new ProjectDto(p.getId(), p.getOrgId(), p.getSlug(), p.getName(), p.getDescription(),
                p.getVisibility(), p.getOwnerUserId(), names.get(p.getOwnerUserId()), p.getStatus(),
                dbKeys, opsKeys, ldt(p.getCreatedAt()), ldt(p.getUpdatedAt()));
    }

    /** 批量回填创建者展示名（displayName 优先，回落 username），缺失用户留 null。 */
    private Map<Long, String> resolveUsernames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (UserEntity u : users.findAllById(userIds)) {
            out.put(u.getId(), u.getDisplayName() != null && !u.getDisplayName().isBlank()
                    ? u.getDisplayName() : u.getUsername());
        }
        return out;
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
