package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.Slugger;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.MenuItemDto;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.entity.MenuItemEntity;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import com.xinl.easyclaw.hub.repository.MenuItemRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公共菜单配置：菜单项绑定工作区（因工作区↔project 1:1，等价于按 project），<b>与组织无关</b>，
 * 同一工作区的菜单对所有 spoke 通用（hub 只配置与下发，不做 per-spoke 控制）。
 * <p>读写鉴权复用 {@link WorkspaceService}（跟随绑定 project 的可见性/编辑权限）。
 */
@Service
public class MenuService {

    private final MenuItemRepository menuItems;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    public MenuService(MenuItemRepository menuItems, WorkspaceService workspaceService, AuditService auditService) {
        this.menuItems = menuItems;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
    }

    /** 菜单项列表（扁平，按 sort_order,id）：读鉴权按工作区可见性。 */
    @Transactional(readOnly = true)
    public List<MenuItemDto> list(Long requesterId, Long workspaceId) {
        workspaceService.requireVisible(workspaceId, requesterId);
        return menuItems.findByWorkspaceIdOrderBySortOrderAscIdAsc(workspaceId).stream().map(this::toDto).toList();
    }

    @Transactional
    public MenuItemDto create(Long requesterId, Long workspaceId, CreateMenuItemRequest req) {
        WorkspaceEntity w = editableWorkspace(workspaceId, requesterId);
        if (req.parentId() != null) {
            requireSameWorkspaceParent(w.getId(), req.parentId());
        }
        String key = resolveKey(w.getId(), req.menuKey(), req.label());
        MenuItemEntity m = new MenuItemEntity();
        m.setWorkspaceId(w.getId());
        m.setParentId(req.parentId());
        m.setMenuKey(key);
        m.setLabel(req.label().trim());
        m.setIcon(blankToEmpty(req.icon()));
        m.setPath(blankToEmpty(req.path()));
        m.setRequiredPerm(blankToEmpty(req.requiredPerm()));
        m.setVisibleRoles(blankToEmpty(req.visibleRoles()));
        m.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        m.setEnabled(req.enabled() == null || req.enabled());
        menuItems.save(m);
        auditService.record(AuditModule.MENU, "create_menu", requesterId, w.getOrgId(), "menu",
                String.valueOf(m.getId()), "workspaceId=" + w.getId() + ",key=" + m.getMenuKey(), AuditModule.SUCCESS);
        return toDto(m);
    }

    @Transactional
    public MenuItemDto update(Long requesterId, Long id, UpdateMenuItemRequest req) {
        MenuItemEntity m = requireMenu(id);
        WorkspaceEntity w = editableWorkspace(m.getWorkspaceId(), requesterId);
        if (req.menuKey() != null && !req.menuKey().isBlank() && !req.menuKey().equals(m.getMenuKey())) {
            if (menuItems.existsByWorkspaceIdAndMenuKey(w.getId(), req.menuKey())) {
                throw ApiException.conflict("menuKey 在该工作区已存在");
            }
            m.setMenuKey(req.menuKey());
        }
        if (req.label() != null && !req.label().isBlank()) {
            m.setLabel(req.label().trim());
        }
        if (req.icon() != null) {
            m.setIcon(blankToEmpty(req.icon()));
        }
        if (req.path() != null) {
            m.setPath(blankToEmpty(req.path()));
        }
        if (req.requiredPerm() != null) {
            m.setRequiredPerm(blankToEmpty(req.requiredPerm()));
        }
        if (req.visibleRoles() != null) {
            m.setVisibleRoles(blankToEmpty(req.visibleRoles()));
        }
        if (req.sortOrder() != null) {
            m.setSortOrder(req.sortOrder());
        }
        if (req.enabled() != null) {
            m.setEnabled(req.enabled());
        }
        menuItems.save(m);
        auditService.record(AuditModule.MENU, "update_menu", requesterId, w.getOrgId(), "menu",
                String.valueOf(m.getId()), "key=" + m.getMenuKey(), AuditModule.SUCCESS);
        return toDto(m);
    }

    /** 启用/停用菜单项（下发时仅含 enabled 项）。 */
    @Transactional
    public MenuItemDto setEnabled(Long requesterId, Long id, boolean enabled) {
        MenuItemEntity m = requireMenu(id);
        WorkspaceEntity w = editableWorkspace(m.getWorkspaceId(), requesterId);
        m.setEnabled(enabled);
        menuItems.save(m);
        auditService.record(AuditModule.MENU, enabled ? "enable_menu" : "disable_menu", requesterId, w.getOrgId(),
                "menu", String.valueOf(m.getId()), "key=" + m.getMenuKey(), AuditModule.SUCCESS);
        return toDto(m);
    }

    /** 删除菜单项，级联删除其全部子孙（避免留下挂空父的孤儿项）。 */
    @Transactional
    public void delete(Long requesterId, Long id) {
        MenuItemEntity m = requireMenu(id);
        WorkspaceEntity w = editableWorkspace(m.getWorkspaceId(), requesterId);
        List<MenuItemEntity> all = menuItems.findByWorkspaceIdOrderBySortOrderAscIdAsc(w.getId());
        Map<Long, List<Long>> childIds = new HashMap<>();
        for (MenuItemEntity it : all) {
            if (it.getParentId() != null) {
                childIds.computeIfAbsent(it.getParentId(), k -> new ArrayList<>()).add(it.getId());
            }
        }
        Set<Long> toDelete = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(m.getId());
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (toDelete.add(cur)) {
                childIds.getOrDefault(cur, List.of()).forEach(stack::push);
            }
        }
        menuItems.deleteAllById(toDelete);
        auditService.record(AuditModule.MENU, "delete_menu", requesterId, w.getOrgId(), "menu",
                String.valueOf(m.getId()), "key=" + m.getMenuKey() + ",cascadeCount=" + toDelete.size(),
                AuditModule.SUCCESS);
    }

    // ---------- 下发用树构建（供 SpokeService 复用，只读，无请求者上下文） ----------

    /** 组装某工作区的下发菜单树：仅 enabled 项，按 sort_order,id 保序；父被禁用/缺失的孤儿项提升为顶层。 */
    @Transactional(readOnly = true)
    public List<SpokeMenuNode> buildTree(Long workspaceId) {
        List<MenuItemEntity> enabled = new ArrayList<>();
        for (MenuItemEntity m : menuItems.findByWorkspaceIdOrderBySortOrderAscIdAsc(workspaceId)) {
            if (Boolean.TRUE.equals(m.getEnabled())) {
                enabled.add(m);
            }
        }
        Set<Long> ids = new HashSet<>();
        enabled.forEach(m -> ids.add(m.getId()));
        Map<Long, List<MenuItemEntity>> byParent = new HashMap<>();
        List<MenuItemEntity> roots = new ArrayList<>();
        for (MenuItemEntity m : enabled) {
            if (m.getParentId() == null || !ids.contains(m.getParentId())) {
                roots.add(m);
            } else {
                byParent.computeIfAbsent(m.getParentId(), k -> new ArrayList<>()).add(m);
            }
        }
        return buildNodes(roots, byParent);
    }

    private List<SpokeMenuNode> buildNodes(List<MenuItemEntity> items, Map<Long, List<MenuItemEntity>> byParent) {
        List<SpokeMenuNode> out = new ArrayList<>();
        for (MenuItemEntity m : items) {
            out.add(new SpokeMenuNode(m.getMenuKey(), m.getLabel(), emptyToNull(m.getIcon()), emptyToNull(m.getPath()),
                    emptyToNull(m.getRequiredPerm()), emptyToNull(m.getVisibleRoles()),
                    buildNodes(byParent.getOrDefault(m.getId(), List.of()), byParent)));
        }
        return out;
    }

    // ---------- 内部 ----------

    private WorkspaceEntity editableWorkspace(Long workspaceId, Long requesterId) {
        WorkspaceEntity w = workspaceService.requireWorkspace(workspaceId);
        workspaceService.requireWorkspaceEditable(w, requesterId);
        return w;
    }

    private MenuItemEntity requireMenu(Long id) {
        return menuItems.findById(id).orElseThrow(() -> ApiException.notFound("菜单项不存在"));
    }

    private void requireSameWorkspaceParent(Long workspaceId, Long parentId) {
        MenuItemEntity parent = requireMenu(parentId);
        if (!parent.getWorkspaceId().equals(workspaceId)) {
            throw ApiException.validation("父菜单项不属于该工作区");
        }
    }

    /** menuKey 留空则由 label 派生（纯中文兜底 "menu"），冲突自动追加 -2/-3…。 */
    private String resolveKey(Long workspaceId, String requested, String label) {
        if (requested != null && !requested.isBlank()) {
            String key = requested.trim();
            if (menuItems.existsByWorkspaceIdAndMenuKey(workspaceId, key)) {
                throw ApiException.conflict("menuKey 在该工作区已存在");
            }
            return key;
        }
        String base = Slugger.derive(label, "menu");
        String candidate = base;
        for (int i = 2; menuItems.existsByWorkspaceIdAndMenuKey(workspaceId, candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private MenuItemDto toDto(MenuItemEntity m) {
        return new MenuItemDto(m.getId(), m.getWorkspaceId(), m.getParentId(), m.getMenuKey(), m.getLabel(),
                m.getIcon(), m.getPath(), m.getRequiredPerm(), m.getVisibleRoles(), m.getSortOrder(), m.getEnabled(),
                ldt(m.getCreatedAt()), ldt(m.getUpdatedAt()));
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static java.time.LocalDateTime ldt(java.time.Instant instant) {
        return instant == null ? null
                : java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
