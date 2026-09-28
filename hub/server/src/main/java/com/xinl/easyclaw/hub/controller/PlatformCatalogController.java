package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.featureflag.CreateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.featureflag.FeatureFlagDto;
import com.xinl.easyclaw.hub.contract.featureflag.UpdateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.MenuItemDto;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.tool.PlatformToolDto;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.FeatureFlagService;
import com.xinl.easyclaw.hub.service.MenuService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import com.xinl.easyclaw.hub.service.ToolCatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台目录端点（/api/platform/**，platformAdmin 专属，PlatformAdminGuard 强制）：
 * 菜单/功能开关目录 CRUD 与工具平台总开关。目录为平台级资源，组织侧只有开关端点
 * （MenuController / FeatureFlagController / ToolController），与此处互不重叠。
 */
@RestController
public class PlatformCatalogController {

    private final MenuService menus;
    private final FeatureFlagService flags;
    private final ToolCatalogService tools;
    private final PlatformAdminGuard platformAdminGuard;

    public PlatformCatalogController(MenuService menus, FeatureFlagService flags,
                                     ToolCatalogService tools, PlatformAdminGuard platformAdminGuard) {
        this.menus = menus;
        this.flags = flags;
        this.tools = tools;
        this.platformAdminGuard = platformAdminGuard;
    }

    // ---- 菜单目录 ----

    @GetMapping("/api/platform/menus")
    public List<MenuItemDto> listMenus() {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return menus.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PostMapping("/api/platform/menus")
    public MenuItemDto createMenu(@Valid @RequestBody CreateMenuItemRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return menus.createCatalogItem(userId, req);
    }

    @PutMapping("/api/platform/menus/{id}")
    public MenuItemDto updateMenu(@PathVariable Long id, @Valid @RequestBody UpdateMenuItemRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return menus.updateCatalogItem(userId, id, req);
    }

    @DeleteMapping("/api/platform/menus/{id}")
    public ResponseEntity<Void> deleteMenu(@PathVariable Long id) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        menus.deleteCatalogItem(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- 功能开关目录 ----

    @GetMapping("/api/platform/flags")
    public List<FeatureFlagDto> listFlags() {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return flags.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PostMapping("/api/platform/flags")
    public FeatureFlagDto createFlag(@Valid @RequestBody CreateFeatureFlagRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return flags.createCatalogItem(userId, req);
    }

    @PutMapping("/api/platform/flags/{id}")
    public FeatureFlagDto updateFlag(@PathVariable Long id, @Valid @RequestBody UpdateFeatureFlagRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return flags.updateCatalogItem(userId, id, req);
    }

    @DeleteMapping("/api/platform/flags/{id}")
    public ResponseEntity<Void> deleteFlag(@PathVariable Long id) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        flags.deleteCatalogItem(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- 工具目录（只读清单 + 平台总开关，不可增删） ----

    @GetMapping("/api/platform/tools")
    public List<PlatformToolDto> listTools() {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return tools.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PutMapping("/api/platform/tools/{id}/enabled")
    public PlatformToolDto setToolEnabled(@PathVariable Long id, @Valid @RequestBody SetToolEnabledRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return tools.setEnabled(userId, id, req);
    }
}
