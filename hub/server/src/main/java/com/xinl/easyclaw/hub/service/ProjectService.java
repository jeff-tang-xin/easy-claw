package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.project.ProjectDto;
import com.xinl.easyclaw.hub.contract.project.UpdateProjectRequest;
import com.xinl.easyclaw.hub.service.OrgService;
import com.xinl.easyclaw.hub.common.Slugger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.repository.ProjectRepository;

/**
 * 项目管理：组织内归类锚点。可见性 private|team|public，按角色 + 归属过滤；归档=status:archived。
 * 项目的新建/修改/归档记审计。
 */
@Service
public class ProjectService {

    private static final Set<String> VALID_VISIBILITY = Set.of("private", "team", "public");
    private static final Set<String> VALID_STATUS = Set.of("active", "archived");

    private final ProjectRepository projects;
    private final OrgService orgService;
    private final AuditService auditService;

    public ProjectService(ProjectRepository projects, OrgService orgService, AuditService auditService) {
        this.projects = projects;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    /** 组织下项目列表：owner/admin 全量；member/guest 见 team+public+自己拥有的 private。 */
    public List<ProjectDto> list(Long requesterId, Long orgId) {
        String role = orgService.roleOf(orgId, requesterId);
        if (role == null) {
            throw ApiException.forbidden("非组织成员");
        }
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        List<ProjectDto> out = new ArrayList<>();
        for (ProjectEntity p : projects.findByOrgId(orgId)) {
            if (canSee(p, requesterId, privileged)) {
                out.add(toDto(p));
            }
        }
        return out;
    }

    public ProjectDto get(Long requesterId, Long projectId) {
        ProjectEntity p = projects.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean privileged = "owner".equals(role) || "admin".equals(role);
        boolean member = role != null;
        // public 项目组织外可读；其余需成员且满足可见性。
        if (!"public".equals(p.getVisibility()) && !member) {
            throw ApiException.forbidden("非组织成员");
        }
        if (member && !canSee(p, requesterId, privileged)) {
            throw ApiException.forbidden("无权限查看该项目");
        }
        return toDto(p);
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
        return toDto(p);
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
        return toDto(p);
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

    /** 显式 slug 校验组织内唯一；留空则从名称派生（纯中文名兜底 "proj"），重名自动追加 -2/-3…。 */
    private String resolveProjectSlug(Long orgId, String name, String requested) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.trim();
            if (!Slugger.isValid(slug)) {
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

    private static boolean canSee(ProjectEntity p, Long requesterId, boolean privileged) {
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

    private static ProjectDto toDto(ProjectEntity p) {
        return new ProjectDto(p.getId(), p.getOrgId(), p.getSlug(), p.getName(), p.getDescription(),
                p.getVisibility(), p.getOwnerUserId(), p.getStatus(), ldt(p.getCreatedAt()), ldt(p.getUpdatedAt()));
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
