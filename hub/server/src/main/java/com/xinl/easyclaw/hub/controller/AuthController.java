package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.auth.ChangePasswordRequest;
import com.xinl.easyclaw.hub.contract.auth.LoginRequest;
import com.xinl.easyclaw.hub.contract.auth.RefreshRequest;
import com.xinl.easyclaw.hub.contract.auth.TokenResponse;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.xinl.easyclaw.hub.service.AuthService;

/**
 * 认证端点（§4.2）。login/refresh 公开（JWT 过滤器放行），logout/change-password 需登录。
 * 公开注册已取消：初始 admin 由启动引导创建，后续用户由平台管理员添加（见 user 包 AdminUserController）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(CurrentUserHolder.requireUserId(), req);
        return ResponseEntity.noContent().build();
    }
}
