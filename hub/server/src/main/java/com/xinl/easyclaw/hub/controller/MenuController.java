package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.menu.OrgMenuSettingDto;
import com.xinl.easyclaw.hub.contract.menu.SetMenuVisibleRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织菜单可见性端点（组织侧只有开关，目录 CRUD 在 PlatformCatalogController）：
 * GET /api/orgs/{orgId}/menu-settings 全量目录 × 生效态（读=成员）；
 * PUT /api/orgs/{orgId}/menu-settings/{menuId} 幂等设置（写=owner/admin，service 层裁决）。
 */
@RestController
public class MenuController {

    private final MenuService menus;

    public MenuController(MenuService menus) {
        this.menus = menus;
    }

    @GetMapping("/api/orgs/{orgId}/menu-settings")
    public List<OrgMenuSettingDto> listSettings(@PathVariable Long orgId) {
        return menus.listSettings(CurrentUserHolder.requireUserId(), orgId);
    }

    @PutMapping("/api/orgs/{orgId}/menu-settings/{menuId}")
    public ResponseEntity<Void> setVisible(@PathVariable Long orgId, @PathVariable Long menuId,
                                           @Valid @RequestBody SetMenuVisibleRequest req) {
        menus.setVisible(CurrentUserHolder.requireUserId(), orgId, menuId, req.visible());
        return ResponseEntity.noContent().build();
    }
}
