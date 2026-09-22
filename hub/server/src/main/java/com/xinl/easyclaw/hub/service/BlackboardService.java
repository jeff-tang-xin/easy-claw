package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.blackboard.BlackboardEntryDto;
import com.xinl.easyclaw.hub.contract.blackboard.CreateBlackboardEntryRequest;
import com.xinl.easyclaw.hub.entity.BlackboardEntryEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.BlackboardEntryRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目共享黑板服务（A4）：追加型条目 + 归档（状态标签），团队共享同一块板。
 *
 * <p>权限与项目可见性对齐（同 {@link KnowledgeService} 口径）：读取按 project 的 private/team/public + 组织角色；
 * 追加与归档需可看到项目且非 guest。归档是条目的状态标签（active→archived），不删除数据。
 * 无外键（V4 决策），project/user 一致性由应用层保证。
 */
@Service
public class BlackboardService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";

    private final BlackboardEntryRepository entries;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final OrgService orgService;
    private final AuditService auditService;

    public BlackboardService(BlackboardEntryRepository entries, ProjectRepository projects,
                             UserRepository users, OrgService orgService, AuditService auditService) {
        this.entries = entries;
        this.projects = projects;
        this.users = users;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    // ---------- 查询 ----------

    /** 活跃条目（未归档），按 id 倒序（最新在前）。 */
    public List<BlackboardEntryDto> listActive(Long requesterId, Long projectId) {
        return listByStatus(requesterId, projectId, STATUS_ACTIVE);
    }

    /** 归档条目，按 id 倒序。 */
    public List<BlackboardEntryDto> listArchives(Long requesterId, Long projectId) {
        return listByStatus(requesterId, projectId, STATUS_ARCHIVED);
    }

    private List<BlackboardEntryDto> listByStatus(Long requesterId, Long projectId, String status) {
        ProjectEntity p = loadProject(projectId);
        requireCanRead(p, requesterId);
        List<BlackboardEntryEntity> rows =
                entries.findByProjectIdAndStatusOrderByIdDesc(projectId, status);
        Map<Long, String> names = resolveUsernames(collectAuthorIds(rows));
        return rows.stream().map(e -> toDto(e, names)).toList();
    }

    /** 单条详情（active/archived 均可读，权限按所属项目可见性）。 */
    public BlackboardEntryDto get(Long requesterId, Long entryId) {
        BlackboardEntryEntity e = entries.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("黑板报条目不存在"));
        ProjectEntity p = loadProject(e.getProjectId());
        requireCanRead(p, requesterId);
        return toDto(e, resolveUsernames(Set.of(e.getAuthorUserId())));
    }

    // ---------- 写入 ----------

    @Transactional
    public BlackboardEntryDto append(Long requesterId, CreateBlackboardEntryRequest req) {
        ProjectEntity p = loadProject(req.projectId());
        requireCanWrite(p, requesterId);

        BlackboardEntryEntity e = new BlackboardEntryEntity();
        e.setProjectId(p.getId());
        e.setContent(req.content().trim());
        e.setAuthorUserId(requesterId);
        e.setStatus(STATUS_ACTIVE);
        entries.save(e);
        auditService.record(AuditModule.BLACKBOARD, "append_blackboard_entry", requesterId, p.getOrgId(),
                "blackboard_entry", String.valueOf(e.getId()), "projectId=" + p.getId(), AuditModule.SUCCESS);
        return toDto(e, resolveUsernames(Set.of(requesterId)));
    }

    @Transactional
    public BlackboardEntryDto archive(Long requesterId, Long entryId) {
        BlackboardEntryEntity e = entries.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("黑板报条目不存在"));
        ProjectEntity p = loadProject(e.getProjectId());
        requireCanManage(e, p, requesterId);
        if (STATUS_ARCHIVED.equals(e.getStatus())) {
            // 幂等：已归档直接返回当前态。
            return toDto(e, resolveUsernames(Set.of(e.getAuthorUserId())));
        }
        e.setStatus(STATUS_ARCHIVED);
        entries.save(e);
        auditService.record(AuditModule.BLACKBOARD, "archive_blackboard_entry", requesterId, p.getOrgId(),
                "blackboard_entry", String.valueOf(entryId), "projectId=" + p.getId(), AuditModule.SUCCESS);
        return toDto(e, resolveUsernames(Set.of(e.getAuthorUserId())));
    }

    /** 取消归档（archived→active，幂等：本就活跃直接返回当前态）。 */
    @Transactional
    public BlackboardEntryDto unarchive(Long requesterId, Long entryId) {
        BlackboardEntryEntity e = entries.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("黑板报条目不存在"));
        ProjectEntity p = loadProject(e.getProjectId());
        requireCanManage(e, p, requesterId);
        if (STATUS_ACTIVE.equals(e.getStatus())) {
            return toDto(e, resolveUsernames(Set.of(e.getAuthorUserId())));
        }
        e.setStatus(STATUS_ACTIVE);
        entries.save(e);
        auditService.record(AuditModule.BLACKBOARD, "unarchive_blackboard_entry", requesterId, p.getOrgId(),
                "blackboard_entry", String.valueOf(entryId), "projectId=" + p.getId(), AuditModule.SUCCESS);
        return toDto(e, resolveUsernames(Set.of(e.getAuthorUserId())));
    }

    // ---------- 权限 ----------

    private void requireCanRead(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限查看该项目的黑板报");
        }
    }

    /** 写：先须能看到项目（收紧 private 越权写），再要求非 guest 角色。 */
    private void requireCanWrite(ProjectEntity p, Long requesterId) {
        if (!canSeeProject(p, requesterId)) {
            throw ApiException.forbidden("无权限操作该项目的黑板报");
        }
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        if ("guest".equals(role)) {
            throw ApiException.forbidden("访客只读，不能写入黑板报");
        }
    }

    /**
     * 归档/取消归档的管理权限（对齐知识库删除口径）：组织 owner/admin 或条目作者本人。
     * 收紧原先「任何非 guest 成员均可归档他人条目」的口子。
     */
    private void requireCanManage(BlackboardEntryEntity e, ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean canManage = "owner".equals(role) || "admin".equals(role)
                || e.getAuthorUserId().equals(requesterId);
        if (!canManage) {
            throw ApiException.forbidden("无权限归档或取消归档该条目");
        }
    }

    /** 项目可见性，口径与 {@link KnowledgeService} 完全一致：public 全开；非 public 需成员；private 仅 owner/admin+创建者。 */
    private boolean canSeeProject(ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean member = role != null;
        if ("public".equals(p.getVisibility())) {
            return true;
        }
        if (!member) {
            return false;
        }
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        return privileged
                || "team".equals(p.getVisibility())
                || p.getOwnerUserId().equals(requesterId);
    }

    // ---------- 辅助 ----------

    private ProjectEntity loadProject(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("项目不存在"));
    }

    private Set<Long> collectAuthorIds(List<BlackboardEntryEntity> rows) {
        Set<Long> ids = new HashSet<>();
        for (BlackboardEntryEntity e : rows) {
            ids.add(e.getAuthorUserId());
        }
        return ids;
    }

    /** 批量回填用户名，displayName 优先回落 username，缺失用户留 null。 */
    private Map<Long, String> resolveUsernames(Set<Long> userIds) {
        Set<Long> ids = userIds == null ? Set.of() : userIds;
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (UserEntity u : users.findAllById(ids)) {
            out.put(u.getId(), u.getDisplayName() != null && !u.getDisplayName().isBlank()
                    ? u.getDisplayName() : u.getUsername());
        }
        return out;
    }

    private static BlackboardEntryDto toDto(BlackboardEntryEntity e, Map<Long, String> names) {
        return new BlackboardEntryDto(e.getId(), e.getProjectId(), e.getContent(), e.getAuthorUserId(),
                names.get(e.getAuthorUserId()), e.getStatus(), ldt(e.getCreatedAt()), ldt(e.getUpdatedAt()));
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}