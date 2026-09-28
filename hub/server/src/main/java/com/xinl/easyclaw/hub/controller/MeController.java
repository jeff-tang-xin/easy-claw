package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.security.Permissions;
import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.contract.user.MeResponse;
import com.xinl.easyclaw.hub.contract.user.RoleMatrixResponse;
import com.xinl.easyclaw.hub.contract.user.UserDto;
import com.xinl.easyclaw.hub.service.OrgService;
import com.xinl.easyclaw.hub.service.PasswordPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.UserRepository;

/**
 * 当前用户端点：GET /api/me 返回用户 + 组织 + 当前组织 + 权限清单（供控制台菜单渲染）。
 */
@RestController
@RequestMapping("/api")
public class MeController {

    private final UserRepository users;
    private final OrgService orgService;
    private final PasswordPolicy passwordPolicy;

    public MeController(UserRepository users, OrgService orgService, PasswordPolicy passwordPolicy) {
        this.users = users;
        this.orgService = orgService;
        this.passwordPolicy = passwordPolicy;
    }

    @GetMapping("/me")
    public MeResponse me() {
        Long userId = CurrentUserHolder.requireUserId();
        UserEntity u = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        List<OrgDto> orgs = orgService.listMyOrgs(userId);
        Long currentOrgId = CurrentUserHolder.currentOrgId();
        if (currentOrgId == null && !orgs.isEmpty()) {
            currentOrgId = orgs.get(0).id();
        }
        String role = currentOrgId == null ? null : orgService.roleOf(currentOrgId, userId);
        Set<String> permissions = Permissions.forUser(role, u.isPlatformAdmin());
        Instant now = Instant.now();
        UserDto user = new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(), u.getStatus(),
                u.isPlatformAdmin(), u.isMustChangePassword(),
                passwordPolicy.expiresAt(u.getPasswordChangedAt()),
                passwordPolicy.isExpiringSoon(u.getPasswordChangedAt(), now));
        return new MeResponse(user, orgs, currentOrgId, permissions);
    }

    /**
     * 只读：组织角色 → 权限码矩阵（含平台管理员叠加权限），供控制台「角色与权限」页展示
     * 菜单-角色-权限关联。登录即可读，数据是全局静态规则，不含任何组织/用户实例数据。
     */
    @GetMapping("/role-matrix")
    public RoleMatrixResponse roleMatrix() {
        List<RoleMatrixResponse.RolePerms> roles = Permissions.ORG_ROLES.stream()
                .map(role -> new RoleMatrixResponse.RolePerms(role, Permissions.forRole(role)))
                .toList();
        return new RoleMatrixResponse(roles, Permissions.PLATFORM_ADMIN_PERMS);
    }
}
