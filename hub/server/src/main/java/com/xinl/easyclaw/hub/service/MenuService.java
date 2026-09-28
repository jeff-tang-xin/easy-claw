package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.Slugger;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.MenuItemDto;
import com.xinl.easyclaw.hub.contract.menu.OrgMenuSettingDto;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.entity.MenuItemEntity;
import com.xinl.easyclaw.hub.entity.OrgMenuSettingEntity;
import com.xinl.easyclaw.hub.repository.MenuItemRepository;
import com.xinl.easyclaw.hub.repository.OrgMenuSettingRepository;
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
 * 平台菜单目录 + 组织可见性开关：
 * <ul>
 *   <li>目录（menu_items）为平台级内置：CRUD 仅 platformAdmin（{@link PlatformAdminGuard} 裁决）；</li>
 *   <li>组织侧（org_menu_settings）只决定「是否显示」：惰性行，无行 = 默认可见，owner/admin 写、成员读；</li>
 *   <li>生效语义（唯一裁决口径）= 平台 enabled AND 组织 visible，spoke 下发按此过滤。</li>
 * </ul>
 */
@Service
public class MenuService {

    private final MenuItemRepository menuItems;
    private final OrgMenuSettingRepository settings;
    private final OrgService orgService;
    private final AuditService auditService;
    private final PlatformAdminGuard platformAdmin;

    public MenuService(MenuItemRepository menuItems, OrgMenuSettingRepository settings,
                       OrgService orgService, AuditService auditService, PlatformAdminGuard platformAdmin) {
        this.menuItems = menuItems;
        this.settings = settings;
        this.orgService = orgService;
        this.auditService = auditService;
        this.platformAdmin = platformAdmin;
    }

    // ---------- 平台目录（platformAdmin） ----------

    /** 平台菜单目录（扁平，按 sort_order,id）。 */
    @Transactional(readOnly = true)
    public List<MenuItemDto> listCatalog(Long requesterId) {
        platformAdmin.require(requesterId);
        return menuItems.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public MenuItemDto createCatalogItem(Long requesterId, CreateMenuItemRequest req) {
        platformAdmin.require(requesterId);
        if (req.parentId() != null) {
            requireMenu(req.parentId());
        }
        String key = resolveKey(req.menuKey(), req.label());
        MenuItemEntity m = new MenuItemEntity();
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
        auditService.record(AuditModule.MENU, "create_platform_menu", requesterId, null, "menu",
                String.valueOf(m.getId()), "key=" + m.getMenuKey(), AuditModule.SUCCESS);
        return toDto(m);
    }

    /** 部分更新；menuKey 创建后不可改（服务端裁决，请求体不含该字段）。 */
    @Transactional
    public MenuItemDto updateCatalogItem(Long requesterId, Long id, UpdateMenuItemRequest req) {
        platformAdmin.require(requesterId);
        MenuItemEntity m = requireMenu(id);
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
        auditService.record(AuditModule.MENU, "update_platform_menu", requesterId, null, "menu",
                String.valueOf(m.getId()), "key=" + m.getMenuKey(), AuditModule.SUCCESS);
        return toDto(m);
    }

    /** 删除菜单项，级联删除其全部子孙（避免留下挂空父的孤儿项）及各组织的可见性开关行。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        platformAdmin.require(requesterId);
        MenuItemEntity m = requireMenu(id);
        List<MenuItemEntity> all = menuItems.findAllByOrderBySortOrderAscIdAsc();
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
        settings.deleteAll(settings.findByMenuIdIn(toDelete));
        auditService.record(AuditModule.MENU, "delete_platform_menu", requesterId, null, "menu",
                String.valueOf(m.getId()), "key=" + m.getMenuKey() + ",cascadeCount=" + toDelete.size(),
                AuditModule.SUCCESS);
    }

    // ---------- 组织可见性（读=成员，写=owner/admin） ----------

    /** 组织菜单可见性：全量目录 × 该组织生效态（无开关行 = 默认可见）。 */
    @Transactional(readOnly = true)
    public List<OrgMenuSettingDto> listSettings(Long requesterId, Long orgId) {
        requireMember(orgId, requesterId);
        Map<Long, Boolean> visibleByMenu = new HashMap<>();
        for (OrgMenuSettingEntity s : settings.findByOrgId(orgId)) {
            visibleByMenu.put(s.getMenuId(), s.getVisible());
        }
        List<OrgMenuSettingDto> out = new ArrayList<>();
        for (MenuItemEntity m : menuItems.findAllByOrderBySortOrderAscIdAsc()) {
            out.add(new OrgMenuSettingDto(m.getId(), m.getMenuKey(), m.getLabel(),
                    visibleByMenu.getOrDefault(m.getId(), true)));
        }
        return out;
    }

    /** 幂等 upsert 组织可见性行；menuId 不存在 → 404。 */
    @Transactional
    public void setVisible(Long requesterId, Long orgId, Long menuId, boolean visible) {
        requireWriter(orgId, requesterId);
        requireMenu(menuId);
        OrgMenuSettingEntity s = settings.findByOrgIdAndMenuId(orgId, menuId).orElseGet(() -> {
            OrgMenuSettingEntity n = new OrgMenuSettingEntity();
            n.setOrgId(orgId);
            n.setMenuId(menuId);
            return n;
        });
        s.setVisible(visible);
        settings.save(s);
        auditService.record(AuditModule.MENU, "set_org_menu_visible", requesterId, orgId, "menu",
                String.valueOf(menuId), "visible=" + visible, AuditModule.SUCCESS);
    }

    // ---------- 下发用树构建（供 SpokeService 复用，只读，无请求者上下文） ----------

    /**
     * 组装某组织的下发菜单树：生效语义 = 平台 enabled AND 组织 visible；
     * 按 sort_order,id 保序；父被过滤/缺失的孤儿项提升为顶层。
     */
    @Transactional(readOnly = true)
    public List<SpokeMenuNode> buildTree(Long orgId) {
        Set<Long> hidden = new HashSet<>();
        for (OrgMenuSettingEntity s : settings.findByOrgId(orgId)) {
            if (!Boolean.TRUE.equals(s.getVisible())) {
                hidden.add(s.getMenuId());
            }
        }
        List<MenuItemEntity> effective = new ArrayList<>();
        for (MenuItemEntity m : menuItems.findAllByOrderBySortOrderAscIdAsc()) {
            if (Boolean.TRUE.equals(m.getEnabled()) && !hidden.contains(m.getId())) {
                effective.add(m);
            }
        }
        Set<Long> ids = new HashSet<>();
        effective.forEach(m -> ids.add(m.getId()));
        Map<Long, List<MenuItemEntity>> byParent = new HashMap<>();
        List<MenuItemEntity> roots = new ArrayList<>();
        for (MenuItemEntity m : effective) {
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

    // ---------- 鉴权 ----------

    private void requireMember(Long orgId, Long userId) {
        if (orgService.roleOf(orgId, userId) == null) {
            throw ApiException.forbidden("非组织成员");
        }
    }

    private void requireWriter(Long orgId, Long userId) {
        orgService.requireOrgRole(orgId, userId, "owner", "admin");
    }

    // ---------- 内部 ----------

    private MenuItemEntity requireMenu(Long id) {
        return menuItems.findById(id).orElseThrow(() -> ApiException.notFound("菜单项不存在"));
    }

    /** menuKey 留空则由 label 派生（纯中文兜底 "menu"），冲突自动追加 -2/-3…。 */
    private String resolveKey(String requested, String label) {
        if (requested != null && !requested.isBlank()) {
            String key = requested.trim();
            if (menuItems.existsByMenuKey(key)) {
                throw ApiException.conflict("menuKey 已存在");
            }
            return key;
        }
        String base = Slugger.derive(label, "menu");
        String candidate = base;
        for (int i = 2; menuItems.existsByMenuKey(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private MenuItemDto toDto(MenuItemEntity m) {
        return new MenuItemDto(m.getId(), m.getParentId(), m.getMenuKey(), m.getLabel(), m.getIcon(), m.getPath(),
                m.getRequiredPerm(), m.getVisibleRoles(), m.getSortOrder(), m.getEnabled());
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
