package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.workspace.CreateWorkspaceRequest;
import com.xinl.easyclaw.hub.contract.workspace.UpdateWorkspaceRequest;
import com.xinl.easyclaw.hub.contract.workspace.WorkspaceDto;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import com.xinl.easyclaw.hub.repository.MenuItemRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.WorkspaceRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作区管理：project 面向 spoke 的扩展面，与 project <b>1:1</b> 绑定。
 * <p>权限完全跟随绑定的 project：读用 {@link ProjectService#canSee} 的可见性矩阵
 * （owner/admin 特权全见；team/public 成员可见；private 仅创建者），写要求 owner/admin 或项目创建者。
 * 新建/修改/归档记审计。
 */
@Service
public class WorkspaceService {

    private static final Set<String> VALID_STATUS = Set.of("active", "archived");

    private final WorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final MenuItemRepository menuItems;
    private final OrgService orgService;
    private final AuditService auditService;

    public WorkspaceService(WorkspaceRepository workspaces, ProjectRepository projects,
                            MenuItemRepository menuItems, OrgService orgService, AuditService auditService) {
        this.workspaces = workspaces;
        this.projects = projects;
        this.menuItems = menuItems;
        this.orgService = orgService;
        this.auditService = auditService;
    }

    /** 建工作区：绑定一个尚无工作区的 project（1:1，重复绑定→409）；写权限同项目编辑（owner/admin 或创建者）。 */
    @Transactional
    public WorkspaceDto create(Long requesterId, CreateWorkspaceRequest req) {
        ProjectEntity project = projects.findById(req.projectId())
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        requireProjectEditable(project, requesterId);
        if (workspaces.existsByProjectId(project.getId())) {
            throw ApiException.conflict("该项目已绑定工作区（1:1）");
        }
        WorkspaceEntity w = new WorkspaceEntity();
        w.setProjectId(project.getId());
        w.setOrgId(project.getOrgId());
        w.setName(req.name() == null || req.name().isBlank() ? project.getName() : req.name().trim());
        w.setStatus("active");
        workspaces.save(w);
        auditService.record(AuditModule.WORKSPACE, "create_workspace", requesterId, w.getOrgId(), "workspace",
                String.valueOf(w.getId()), "projectId=" + w.getProjectId() + ",name=" + w.getName(), AuditModule.SUCCESS);
        return toDto(w);
    }

    /** 组织下工作区列表：按绑定 project 的可见性过滤（owner/admin 全量；其余见 team+public+自己 private）。 */
    @Transactional(readOnly = true)
    public List<WorkspaceDto> list(Long requesterId, Long orgId) {
        String role = orgService.roleOf(orgId, requesterId);
        if (role == null) {
            throw ApiException.forbidden("非组织成员");
        }
        boolean privileged = isPrivileged(role);
        return workspaces.findByOrgId(orgId).stream()
                .filter(w -> {
                    ProjectEntity p = projects.findById(w.getProjectId()).orElse(null);
                    return p != null && ProjectService.canSee(p, requesterId, privileged);
                })
                .map(this::toDto)
                .toList();
    }

    public WorkspaceDto get(Long requesterId, Long id) {
        WorkspaceEntity w = requireVisible(id, requesterId);
        return toDto(w);
    }

    /** 改工作区：name 改展示名，status 归档/恢复；写权限同项目编辑。 */
    @Transactional
    public WorkspaceDto update(Long requesterId, Long id, UpdateWorkspaceRequest req) {
        WorkspaceEntity w = requireWorkspace(id);
        requireWorkspaceEditable(w, requesterId);
        if (req.name() != null && !req.name().isBlank()) {
            w.setName(req.name().trim());
        }
        if (req.status() != null) {
            if (!VALID_STATUS.contains(req.status())) {
                throw ApiException.validation("非法状态：" + req.status());
            }
            w.setStatus(req.status());
        }
        workspaces.save(w);
        auditService.record(AuditModule.WORKSPACE, "update_workspace", requesterId, w.getOrgId(), "workspace",
                String.valueOf(w.getId()), "name=" + w.getName() + ",status=" + w.getStatus(), AuditModule.SUCCESS);
        return toDto(w);
    }

    /** 归档工作区（软删）并级联删除其菜单配置。 */
    @Transactional
    public void archive(Long requesterId, Long id) {
        WorkspaceEntity w = requireWorkspace(id);
        requireWorkspaceEditable(w, requesterId);
        w.setStatus("archived");
        workspaces.save(w);
        menuItems.deleteByWorkspaceId(w.getId());
        auditService.record(AuditModule.WORKSPACE, "archive_workspace", requesterId, w.getOrgId(), "workspace",
                String.valueOf(w.getId()), null, AuditModule.SUCCESS);
    }

    // ---------- 供 MenuService / SpokeService 复用的加载与鉴权 ----------

    /** 加载工作区，不存在→404。 */
    public WorkspaceEntity requireWorkspace(Long id) {
        return workspaces.findById(id).orElseThrow(() -> ApiException.notFound("工作区不存在"));
    }

    /** 读鉴权：按绑定 project 的可见性矩阵；不可见→403。返回实体供后续使用。 */
    public WorkspaceEntity requireVisible(Long id, Long requesterId) {
        WorkspaceEntity w = requireWorkspace(id);
        ProjectEntity p = projects.findById(w.getProjectId())
                .orElseThrow(() -> ApiException.notFound("工作区绑定的项目不存在"));
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean privileged = isPrivileged(role);
        boolean member = role != null;
        if (!"public".equals(p.getVisibility()) && !member) {
            throw ApiException.forbidden("非组织成员");
        }
        if (member && !ProjectService.canSee(p, requesterId, privileged)) {
            throw ApiException.forbidden("无权限查看该工作区");
        }
        return w;
    }

    /** 写鉴权：owner/admin 或绑定项目的创建者可改；否则 403。 */
    public void requireWorkspaceEditable(WorkspaceEntity w, Long requesterId) {
        ProjectEntity p = projects.findById(w.getProjectId())
                .orElseThrow(() -> ApiException.notFound("工作区绑定的项目不存在"));
        requireProjectEditable(p, requesterId);
    }

    private void requireProjectEditable(ProjectEntity p, Long requesterId) {
        String role = orgService.roleOf(p.getOrgId(), requesterId);
        boolean canEdit = isPrivileged(role) || p.getOwnerUserId().equals(requesterId);
        if (!canEdit) {
            throw ApiException.forbidden("无权限修改该工作区");
        }
    }

    private static boolean isPrivileged(String role) {
        return "owner".equals(role) || "admin".equals(role);
    }

    private WorkspaceDto toDto(WorkspaceEntity w) {
        long menuCount = menuItems.findByWorkspaceIdOrderBySortOrderAscIdAsc(w.getId()).size();
        return new WorkspaceDto(w.getId(), w.getProjectId(), w.getOrgId(), w.getName(), w.getStatus(),
                menuCount, ldt(w.getCreatedAt()), ldt(w.getUpdatedAt()));
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
