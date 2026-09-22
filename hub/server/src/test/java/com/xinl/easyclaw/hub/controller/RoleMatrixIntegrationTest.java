package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/role-matrix：角色→权限码只读矩阵。
 * 该端点返回的是服务端 Permissions 的权威静态规则，任何登录用户可读（无组织角色也可读）。
 */
class RoleMatrixIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private JsonNode matrix(String token) throws Exception {
        String json = getJson("/api/role-matrix", token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    private static Set<String> permsOf(JsonNode body, String role) {
        for (JsonNode rp : body.get("roles")) {
            if (rp.get("role").asText().equals(role)) {
                Set<String> perms = new HashSet<>();
                rp.get("permissions").forEach(p -> perms.add(p.asText()));
                return perms;
            }
        }
        throw new IllegalStateException("role not found: " + role);
    }

    @Test
    void unauthenticated_unauthorized() throws Exception {
        getJson("/api/role-matrix", null).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsFixedRoleOrder_andAuthoritativePermissions() throws Exception {
        // 无任何组织角色的普通登录用户也能读（全局静态规则，不涉及实例数据）
        String token = createUserAndLogin("rm_user1", PW);
        JsonNode body = matrix(token);

        assertThat(body.get("roles")).hasSize(4);
        // 顺序固定为 owner/admin/member/guest
        assertThat(body.get("roles").get(0).get("role").asText()).isEqualTo("owner");
        assertThat(body.get("roles").get(1).get("role").asText()).isEqualTo("admin");
        assertThat(body.get("roles").get(2).get("role").asText()).isEqualTo("member");
        assertThat(body.get("roles").get(3).get("role").asText()).isEqualTo("guest");

        // owner 拥有删除组织权限；admin 有治理权但无 org.delete
        assertThat(permsOf(body, "owner")).contains("org.delete", "appkey.manage", "audit.read");
        assertThat(permsOf(body, "admin")).contains("org.manage", "appkey.manage").doesNotContain("org.delete");

        // member 可自助管本人 AppKey、可写项目，但无组织治理 / 全量 AppKey 治理 / 审计
        Set<String> member = permsOf(body, "member");
        assertThat(member).contains("appkey.self", "project.write")
                .doesNotContain("org.manage", "appkey.manage", "audit.read", "org.delete");

        // guest 只读：有 org.read / project.read，无任何写权限与 AppKey 自助权限
        Set<String> guest = permsOf(body, "guest");
        assertThat(guest).contains("org.read", "project.read")
                .doesNotContain("project.write", "appkey.self", "org.manage");

        // 平台管理员叠加权限：目录管理 + 用户管理 + 提供商管理
        assertThat(body.get("platformAdminPerms")).isNotNull();
        Set<String> platform = new HashSet<>();
        body.get("platformAdminPerms").forEach(p -> platform.add(p.asText()));
        assertThat(platform).containsExactlyInAnyOrder("platform.catalog.manage", "user.manage", "provider.manage");
    }

    @Test
    void platformAdminFlagUser_seesSameStaticMatrix() throws Exception {
        // 平台管理员标志只叠加 user.manage/provider.manage 两项，矩阵对其仍返回全局静态规则
        createPlatformAdminOk("rm_admin1", PW);
        String token = loginOk("rm_admin1", PW).accessToken();
        getJson("/api/role-matrix", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdminPerms[0]").exists());
    }
}
