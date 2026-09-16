package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.auth.ChangePasswordRequest;
import com.xinl.easyclaw.hub.contract.auth.LoginRequest;
import com.xinl.easyclaw.hub.contract.auth.RefreshRequest;
import com.xinl.easyclaw.hub.contract.auth.TokenResponse;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.user.AdminCreateUserRequest;
import com.xinl.easyclaw.hub.service.PasswordMailer;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证流端到端：登录 / JWT 过滤器放行与拦截 / refresh 旋转 / logout 吊销 /
 * 首登强制改密 / 平台管理员开通用户（临时密码投递）。
 * 公开注册已取消，只验证端点不复存在。用户名统一 auth_ 前缀。
 */
class AuthFlowIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";
    private static final String NEW_PW = "newpassword456";

    /** 捕获临时密码投递，替代 LoggingPasswordMailer（否则密码只出现在控制台，测试拿不到）。 */
    @MockitoBean
    private PasswordMailer passwordMailer;

    // ---------- 注册已取消 ----------

    @Test
    void register_endpointRemoved() throws Exception {
        // 无 token：不在过滤器公开路径内 → 401
        postJson("/api/auth/register", Map.of("username", "auth_x", "password", PW), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        // 有 token：端点已删除 → 404
        String token = createUserAndLogin("auth_legacy", PW);
        postJson("/api/auth/register", Map.of("username", "auth_x", "password", PW), token)
                .andExpect(status().isNotFound());
    }

    // ---------- 登录 ----------

    @Test
    void login_success_issuesTokenPair() throws Exception {
        createUserOk("auth_bob", null, PW);
        TokenResponse t = loginOk("auth_bob", PW);
        assertFalse(t.accessToken().isBlank());
        assertFalse(t.refreshToken().isBlank());
        assertEquals("Bearer", t.tokenType());
        assertEquals(1800L, t.expiresIn());
        assertEquals("auth_bob", t.user().username());
        assertFalse(t.user().mustChangePassword());
        assertTrue(t.orgs().isEmpty());
    }

    @Test
    void login_failure_sameErrorForUnknownUserAndWrongPassword() throws Exception {
        createUserOk("auth_carol", null, PW);
        byte[] wrongPw = postJson("/api/auth/login", new LoginRequest("auth_carol", "wrong-password"), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] unknownUser = postJson("/api/auth/login", new LoginRequest("auth_nobody", PW), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andReturn().getResponse().getContentAsByteArray();
        // 防用户枚举：两种失败的响应体必须逐字节一致。
        assertArrayEquals(wrongPw, unknownUser);
    }

    // ---------- 过滤器 ----------

    @Test
    void protectedEndpoint_noToken_authRequired() throws Exception {
        getJson("/api/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void protectedEndpoint_garbageToken_authInvalid() throws Exception {
        getJson("/api/me", "not.a.jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void me_validToken_returnsUserOrgsPermissions() throws Exception {
        String token = createUserAndLogin("auth_dave", PW);
        getJson("/api/me", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("auth_dave"))
                .andExpect(jsonPath("$.user.platformAdmin").value(false))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andExpect(jsonPath("$.orgs").isArray())
                .andExpect(jsonPath("$.permissions").isArray());
    }

    // ---------- refresh / logout ----------

    @Test
    void refresh_rotates_oldTokenRejected() throws Exception {
        createUserOk("auth_erin", null, PW);
        TokenResponse first = loginOk("auth_erin", PW);

        TokenResponse second = om.readValue(postJson("/api/auth/refresh",
                        new RefreshRequest(first.refreshToken()), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), TokenResponse.class);
        assertNotEquals(first.refreshToken(), second.refreshToken());

        // 旧 refresh 已吊销，重放必须 401。
        postJson("/api/auth/refresh", new RefreshRequest(first.refreshToken()), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        // 新 refresh 可用。
        postJson("/api/auth/refresh", new RefreshRequest(second.refreshToken()), null)
                .andExpect(status().isOk());
    }

    @Test
    void refresh_unknownToken_authInvalid() throws Exception {
        postJson("/api/auth/refresh", new RefreshRequest("no-such-refresh-token"), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void logout_revokesRefreshToken() throws Exception {
        createUserOk("auth_fred", null, PW);
        TokenResponse t = loginOk("auth_fred", PW);
        postJson("/api/auth/logout", new RefreshRequest(t.refreshToken()), t.accessToken())
                .andExpect(status().isNoContent());
        postJson("/api/auth/refresh", new RefreshRequest(t.refreshToken()), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void logout_requiresLogin() throws Exception {
        // logout 不在过滤器公开路径内：无 access token 直接 401。
        postJson("/api/auth/logout", new RefreshRequest("whatever"), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ---------- 改密 ----------

    @Test
    void changePassword_wrongOld_validation() throws Exception {
        createUserOk("auth_gina", null, PW);
        TokenResponse t = loginOk("auth_gina", PW);
        postJson("/api/auth/change-password", new ChangePasswordRequest("wrong-old", NEW_PW), t.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void changePassword_success_clearsFlagAndRevokesRefresh() throws Exception {
        createUserMustChangeOk("auth_hank", null, PW);
        // 待改密用户可正常登录，由前端据此跳转强制改密页。
        TokenResponse t = loginOk("auth_hank", PW);
        assertTrue(t.user().mustChangePassword());

        postJson("/api/auth/change-password", new ChangePasswordRequest(PW, NEW_PW), t.accessToken())
                .andExpect(status().isNoContent());

        // 改密吊销全部 refresh token。
        postJson("/api/auth/refresh", new RefreshRequest(t.refreshToken()), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        // 旧密码失效，新密码可登录且标记已清除。
        postJson("/api/auth/login", new LoginRequest("auth_hank", PW), null)
                .andExpect(status().isUnauthorized());
        TokenResponse t2 = loginOk("auth_hank", NEW_PW);
        assertFalse(t2.user().mustChangePassword());
    }

    // ---------- 平台管理员开通用户 ----------

    @Test
    void adminCreateUser_forbiddenForNonAdmin() throws Exception {
        String token = createUserAndLogin("auth_iris", PW);
        postJson("/api/users", new AdminCreateUserRequest("auth_new1", "auth_new1@example.com", "New", null, null), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCreateUser_duplicateUsername_conflict() throws Exception {
        createUserOk("auth_dup", null, PW);
        createPlatformAdminOk("auth_admin1", PW);
        String adminToken = loginOk("auth_admin1", PW).accessToken();
        postJson("/api/users", new AdminCreateUserRequest("auth_dup", "auth_dup2@example.com", "Dup", null, null), adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void adminCreateUser_tempPasswordMailed_firstLoginForcesChange() throws Exception {
        createPlatformAdminOk("auth_admin2", PW);
        String adminToken = loginOk("auth_admin2", PW).accessToken();

        postJson("/api/users", new AdminCreateUserRequest("auth_newbie", "auth_newbie@example.com", "Newbie", null, null),
                adminToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("auth_newbie"))
                .andExpect(jsonPath("$.email").value("auth_newbie@example.com"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.platformAdmin").value(false));

        // 临时密码经 PasswordMailer 投递邮箱，不出现在响应体。
        ArgumentCaptor<String> pwCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordMailer).sendInitialPassword(eq("auth_newbie@example.com"), eq("auth_newbie"),
                pwCaptor.capture());
        String tempPw = pwCaptor.getValue();
        assertFalse(tempPw.isBlank());

        // 新用户首登：标记强制改密 → 改密 → 旧临时密码失效、新密码可登录。
        TokenResponse t = loginOk("auth_newbie", tempPw);
        assertTrue(t.user().mustChangePassword());
        postJson("/api/auth/change-password", new ChangePasswordRequest(tempPw, NEW_PW), t.accessToken())
                .andExpect(status().isNoContent());
        postJson("/api/auth/login", new LoginRequest("auth_newbie", tempPw), null)
                .andExpect(status().isUnauthorized());
        assertFalse(loginOk("auth_newbie", NEW_PW).user().mustChangePassword());
    }

    @Test
    void adminListUsers_adminOnly() throws Exception {
        createPlatformAdminOk("auth_admin3", PW);
        String adminToken = loginOk("auth_admin3", PW).accessToken();
        getJson("/api/users", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        String token = createUserAndLogin("auth_judy", PW);
        getJson("/api/users", token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCreateUser_withOrg_joinsOrgImmediately() throws Exception {
        createPlatformAdminOk("auth_admin4", PW);
        String adminToken = loginOk("auth_admin4", PW).accessToken();
        String ownerToken = createUserAndLogin("auth_owner4", PW);
        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Auth Org4", "auth-org4"), ownerToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();

        // 建用户即入组（默认 member）：新用户改密登录后 /api/me 可见该组织。
        postJson("/api/users",
                        new AdminCreateUserRequest("auth_orgnew", "auth_orgnew@example.com", "OrgNew", orgId, null),
                        adminToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("auth_orgnew"));

        ArgumentCaptor<String> pwCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordMailer).sendInitialPassword(eq("auth_orgnew@example.com"), eq("auth_orgnew"),
                pwCaptor.capture());
        TokenResponse t = loginOk("auth_orgnew", pwCaptor.getValue());
        postJson("/api/auth/change-password", new ChangePasswordRequest(pwCaptor.getValue(), NEW_PW), t.accessToken())
                .andExpect(status().isNoContent());
        TokenResponse t2 = loginOk("auth_orgnew", NEW_PW);
        String meJson = getJson("/api/me", t2.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(meJson.contains("\"id\":" + orgId), "建用户带 orgId 应立即入组，/api/me 可见该组织");

        // 指定角色 admin 入组；不存在的 orgId → 400；owner 角色直接拒绝 → 400。
        postJson("/api/users",
                        new AdminCreateUserRequest("auth_orgadm", "auth_orgadm@example.com", "OrgAdm", orgId, "admin"),
                        adminToken)
                .andExpect(status().isCreated());
        postJson("/api/users",
                        new AdminCreateUserRequest("auth_noorg", "auth_noorg@example.com", "NoOrg", 999999999L, null),
                        adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        postJson("/api/users",
                        new AdminCreateUserRequest("auth_owner2", "auth_owner2@example.com", "Owner2", orgId, "owner"),
                        adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void adminOrgOptions_adminOnly() throws Exception {
        createPlatformAdminOk("auth_admin5", PW);
        String adminToken = loginOk("auth_admin5", PW).accessToken();
        String ownerToken = createUserAndLogin("auth_owner5", PW);
        postJson("/api/orgs", new CreateOrgRequest("Auth Org5", "auth-org5"), ownerToken)
                .andExpect(status().isOk());

        String body = getJson("/api/users/org-options", adminToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"slug\":\"auth-org5\""), "平台管理员应见全量组织选项");

        getJson("/api/users/org-options", ownerToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
