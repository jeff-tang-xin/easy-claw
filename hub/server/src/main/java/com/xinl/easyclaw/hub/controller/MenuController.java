package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.MenuItemDto;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共菜单配置端点（管理面，JWT）：菜单项归属工作区，与组织无关。
 * 新增走 {@code /api/workspaces/{id}/menus}（工作区作用域），改/删/启停走 {@code /api/menus/{id}}（按项 id）。
 */
@RestController
@RequestMapping("/api")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/workspaces/{id}/menus")
    public List<MenuItemDto> list(@PathVariable Long id) {
        return menuService.list(CurrentUserHolder.requireUserId(), id);
    }

    @PostMapping("/workspaces/{id}/menus")
    public MenuItemDto create(@PathVariable Long id, @Valid @RequestBody CreateMenuItemRequest req) {
        return menuService.create(CurrentUserHolder.requireUserId(), id, req);
    }

    @PatchMapping("/menus/{menuId}")
    public MenuItemDto update(@PathVariable Long menuId, @Valid @RequestBody UpdateMenuItemRequest req) {
        return menuService.update(CurrentUserHolder.requireUserId(), menuId, req);
    }

    /** 启用/停用菜单项（?enabled=true|false）。 */
    @PostMapping("/menus/{menuId}/toggle")
    public MenuItemDto toggle(@PathVariable Long menuId, @RequestParam boolean enabled) {
        return menuService.setEnabled(CurrentUserHolder.requireUserId(), menuId, enabled);
    }

    @DeleteMapping("/menus/{menuId}")
    public ResponseEntity<Void> delete(@PathVariable Long menuId) {
        menuService.delete(CurrentUserHolder.requireUserId(), menuId);
        return ResponseEntity.noContent().build();
    }
}
