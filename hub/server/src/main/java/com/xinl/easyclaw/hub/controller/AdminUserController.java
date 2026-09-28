package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.service.AuthService;
import com.xinl.easyclaw.hub.contract.org.OrgOptionDto;
import com.xinl.easyclaw.hub.contract.user.AdminCreateUserRequest;
import com.xinl.easyclaw.hub.contract.user.CreatedUserDto;
import com.xinl.easyclaw.hub.contract.user.UserDto;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员用户管理端点：用户列表 + 创建用户（一次性随机密码随响应返回，首登强制改密）+
 * 重置密码（一次性随机密码随响应返回，重置后强制改密）+ 删除用户。仅 platformAdmin 可用。
 */
@RestController
@RequestMapping("/api/users")
public class AdminUserController {

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public List<UserDto> list() {
        return authService.adminListUsers(CurrentUserHolder.requireUserId());
    }

    @PostMapping
    public ResponseEntity<CreatedUserDto> create(@Valid @RequestBody AdminCreateUserRequest req) {
        return ResponseEntity.status(201).body(authService.adminCreateUser(CurrentUserHolder.requireUserId(), req));
    }

    /** 重置用户密码：返回一次性新密码（仅本次可见），重置后该用户下次登录强制改密。 */
    @PostMapping("/{id}/reset-password")
    public CreatedUserDto resetPassword(@PathVariable Long id) {
        return authService.adminResetPassword(CurrentUserHolder.requireUserId(), id);
    }

    /** 全量组织选项（仅 platformAdmin）：添加用户/创建 provider 表单的组织下拉数据源。 */
    @GetMapping("/org-options")
    public List<OrgOptionDto> orgOptions() {
        return authService.adminOrgOptions(CurrentUserHolder.requireUserId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        authService.adminDeleteUser(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
