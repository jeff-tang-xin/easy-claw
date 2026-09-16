package com.xinl.easyclaw.hub.config;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.UserRepository;
import com.xinl.easyclaw.hub.service.AuditService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AdminBootstrap 纯 Mockito 单测（不起 Spring 上下文）：
 * 已有平台管理员 → 不动；存在名为 admin 的用户 → 提升为平台管理员且密码不变；
 * 否则创建唯一 admin（随机临时密码、首登强制改密），不派生 admin-2/-3。
 */
class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminBootstrap bootstrap = new AdminBootstrap(users, passwordEncoder, auditService);

    @Test
    void run_platformAdminAlreadyExists_doesNothing() {
        when(users.existsByPlatformAdminTrue()).thenReturn(true);

        bootstrap.run(null);

        verify(users, never()).findByUsername(anyString());
        verify(users, never()).save(any());
        verifyNoInteractions(passwordEncoder, auditService);
    }

    @Test
    void run_adminUserExists_promotesWithoutTouchingPassword() {
        when(users.existsByPlatformAdminTrue()).thenReturn(false);
        UserEntity existing = new UserEntity();
        existing.setId(7L);
        existing.setUsername("admin");
        existing.setPasswordHash("original-hash");
        when(users.findByUsername("admin")).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        assertTrue(existing.isPlatformAdmin());
        assertEquals("original-hash", existing.getPasswordHash(), "提升不得重置密码");
        verify(users).save(same(existing));
        verifyNoInteractions(passwordEncoder);
        verify(auditService).record(eq(AuditModule.AUTH), eq("bootstrap_admin"), eq(7L), isNull(),
                eq("user"), eq("7"), eq("promote existing username=admin"), eq(AuditModule.SUCCESS));
    }

    @Test
    void run_noAdminUser_createsUniqueAdminWithTempPassword() {
        when(users.existsByPlatformAdminTrue()).thenReturn(false);
        when(users.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-hash");

        bootstrap.run(null);

        // 用户名唯一 admin：只查一次，不派生 admin-2/-3。
        verify(users, times(1)).findByUsername(anyString());
        ArgumentCaptor<String> rawPassword = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawPassword.capture());
        assertFalse(rawPassword.getValue().isBlank(), "临时密码不应为空");

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).save(saved.capture());
        UserEntity u = saved.getValue();
        assertEquals("admin", u.getUsername());
        assertEquals("平台管理员", u.getDisplayName());
        assertEquals("encoded-hash", u.getPasswordHash());
        assertTrue(u.isPlatformAdmin());
        assertTrue(u.isMustChangePassword());

        verify(auditService).record(eq(AuditModule.AUTH), eq("bootstrap_admin"), any(), isNull(),
                eq("user"), any(), eq("username=admin"), eq(AuditModule.SUCCESS));
    }
}
