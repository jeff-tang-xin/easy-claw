package com.xinl.easyclaw.hub.users;

import com.xinl.easyclaw.hub.contract.auth.LoginRequest;
import com.xinl.easyclaw.hub.contract.auth.RefreshRequest;
import com.xinl.easyclaw.hub.contract.auth.TokenResponse;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.repository.MembershipRepository;
import com.xinl.easyclaw.hub.repository.RefreshTokenRepository;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台管理员删除用户（DELETE /api/users/{id} → AuthService.adminDeleteUser）：
 * 物理删除 + 级联清理 memberships 与 refresh token（库层无外键，应用层保证一致性）；
 * 保护：不能删自己、不能删平台管理员、仅 platformAdmin 可用、目标不存在 404。
 * 遗留行为（AuthService 注释明言）：被删用户已签发的 access token 在 TTL 内仍有效，
 * 故删除后的失效断言只落在 refresh 链路与库行，不断言 access 立即失效。
 * 用户名统一 du_ 前缀，slug 统一 du- 前缀。
 */
class AdminDeleteUserIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private String newPlatformAdminToken(String username) throws Exception {
        createPlatformAdminOk(username, PW);
        return loginOk(username, PW).accessToken();
    }

    /** owner 建组织并断言 200 + role=owner，返回 orgId。 */
    private long createOrgOk(String ownerToken, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("owner"))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    @Test
    void delete_cascadesMembershipsAndRefreshTokens() throws Exception {
        String admin = newPlatformAdminToken("du_adm1");
        long targetId = createUserOk("du_user1", "du_user1@example.com", PW);
        TokenResponse tokens = loginOk("du_user1", PW);

        // 目标用户先加入某组织、且持有未吊销 refresh token。
        String owner = createUserAndLogin("du_owner1", PW);
        long orgId = createOrgOk(owner, "Du Org1", "du-org1");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("du_user1", "member"), owner)
                .andExpect(status().isCreated());
        assertTrue(membershipRepository.existsByOrgIdAndUserId(orgId, targetId));
        assertFalse(refreshTokenRepository.findByUserId(targetId).isEmpty());

        deleteJson("/api/users/" + targetId, admin)
                .andExpect(status().isNoContent());

        // 用户行、成员关系、refresh token 全部级联清理。
        assertTrue(userRepository.findById(targetId).isEmpty(), "用户行应被物理删除");
        assertTrue(membershipRepository.findByUserId(targetId).isEmpty(), "memberships 应被级联清空");
        assertTrue(refreshTokenRepository.findByUserId(targetId).isEmpty(), "refresh token 应被级联清空");

        // refresh 已清 → 401；用户名再登录 → 401（用户不存在与密码错误同一报错）。
        postJson("/api/auth/refresh", new RefreshRequest(tokens.refreshToken()), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        postJson("/api/auth/login", new LoginRequest("du_user1", PW), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void delete_self_forbidden() throws Exception {
        long adminId = createPlatformAdminOk("du_adm2", PW);
        String admin = loginOk("du_adm2", PW).accessToken();
        deleteJson("/api/users/" + adminId, admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(userRepository.findById(adminId).isPresent(), "删自己被拒后用户应仍在");
    }

    @Test
    void delete_notPlatformAdmin_forbidden() throws Exception {
        // 组织 owner ≠ 平台管理员：坐实 owner 身份后删人仍 403。
        String owner = createUserAndLogin("du_owner3", PW);
        createOrgOk(owner, "Du Org3", "du-org3");
        long targetId = createUserOk("du_user3", null, PW);
        deleteJson("/api/users/" + targetId, owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(userRepository.findById(targetId).isPresent());
    }

    @Test
    void delete_notFound() throws Exception {
        String admin = newPlatformAdminToken("du_adm4");
        deleteJson("/api/users/999999999", admin)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void delete_platformAdminTarget_forbidden() throws Exception {
        String admin = newPlatformAdminToken("du_adm5");
        long otherAdminId = createPlatformAdminOk("du_adm5b", PW);
        deleteJson("/api/users/" + otherAdminId, admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertTrue(userRepository.findById(otherAdminId).isPresent(), "平台管理员不应被删除");
    }
}
