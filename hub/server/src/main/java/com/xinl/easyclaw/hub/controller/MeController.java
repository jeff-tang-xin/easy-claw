package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.security.Permissions;
import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.contract.user.MeResponse;
import com.xinl.easyclaw.hub.contract.user.UserDto;
import com.xinl.easyclaw.hub.service.OrgService;
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

    public MeController(UserRepository users, OrgService orgService) {
        this.users = users;
        this.orgService = orgService;
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
        UserDto user = new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(), u.getStatus(),
                u.isPlatformAdmin(), u.isMustChangePassword());
        return new MeResponse(user, orgs, currentOrgId, permissions);
    }
}
