package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerCategoryRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerGrantRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerCategoryRequest;
import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维服务器分类标签字典（/api/platform/ops-server-categories，仅 platformAdmin）与
 * 服务器目录 category 收口（V28）：标签全局唯一、重命名同步 ops_servers.category 引用行、
 * 被引用时禁止删除（409）；服务器创建/更新的 category 必须是受管标签（空串 = 未标注兼容）；
 * spoke 下发（GET /api/spoke/ops-servers）携带 category。
 * 用户名统一 osc_ 前缀，标签/服务器标识统一 osc- 前缀（目录全局共享，跨用例持久）；
 * 断言稳定错误码，不断言中文文案。
 */
class OpsServerCategoryIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private String adminLogin(String tag) throws Exception {
        createPlatformAdminOk("osc_" + tag + "_admin", PW);
        return loginOk("osc_" + tag + "_admin", PW).accessToken();
    }

    private String userLogin(String tag) throws Exception {
        createUserAndLogin("osc_" + tag + "_user", PW);
        return loginOk("osc_" + tag + "_user", PW).accessToken();
    }

    private long createOrg(String token, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createCategory(String admin, String name) throws Exception {
        String json = postJson("/api/platform/ops-server-categories",
                new CreateOpsServerCategoryRequest(name, null), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createServer(String admin, String tag, long orgId, String category) throws Exception {
        String json = postJson("/api/platform/ops-servers",
                new CreateOpsServerRequest("osc-" + tag, "主机 " + tag, "10.0.0.1", null, null,
                        null, null, category, null, null, orgId, 0L, null), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private ResultActions spokeGet(String url, String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + (appKey == null ? "" : appKey)));
    }

    private JsonNode findRow(String json, String keyField, String keyValue) throws Exception {
        for (JsonNode n : om.readTree(json)) {
            if (keyValue.equals(n.get(keyField).asText())) {
                return n;
            }
        }
        return null;
    }

    // ---------- 平台管理员门槛 ----------

    @Test
    void categoryEndpoints_requirePlatformAdmin() throws Exception {
        String admin = adminLogin("gate");
        String user = userLogin("gate");
        getJson("/api/platform/ops-server-categories", user).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/platform/ops-server-categories",
                new CreateOpsServerCategoryRequest("osc-gate-x", null), user).andExpect(status().isForbidden());
        putJson("/api/platform/ops-server-categories/999999",
                new UpdateOpsServerCategoryRequest(null, null), user).andExpect(status().isForbidden());
        deleteJson("/api/platform/ops-server-categories/999999", user).andExpect(status().isForbidden());
        getJson("/api/platform/ops-server-categories", admin).andExpect(status().isOk());
    }

    // ---------- 标签 CRUD：唯一 + 重命名同步引用行 + 删除拦截 ----------

    @Test
    void categories_crud_uniqueRenameSyncAndDeleteGuard() throws Exception {
        String admin = adminLogin("crud");
        long orgId = createOrg(admin, "Osc Crud", "osc-crud");
        long catId = createCategory(admin, "osc-数据库");

        // name 全局唯一 → 409
        postJson("/api/platform/ops-server-categories",
                new CreateOpsServerCategoryRequest("osc-数据库", 5), admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        // 服务器引用该标签后重命名 → 引用行同步
        long serverId = createServer(admin, "crud-a", orgId, "osc-数据库");
        putJson("/api/platform/ops-server-categories/" + catId,
                new UpdateOpsServerCategoryRequest("osc-数据库2", 7), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("osc-数据库2"))
                .andExpect(jsonPath("$.sortOrder").value(7));
        String servers = getJson("/api/platform/ops-servers", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode row = findRow(servers, "serverKey", "osc-crud-a");
        assertThat(row).isNotNull();
        assertThat(row.get("category").asText()).isEqualTo("osc-数据库2");

        // 被引用的标签禁止删除 → 409；未引用的可删；复删 → 404
        deleteJson("/api/platform/ops-server-categories/" + catId, admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        long tmpId = createCategory(admin, "osc-临时");
        deleteJson("/api/platform/ops-server-categories/" + tmpId, admin).andExpect(status().isNoContent());
        deleteJson("/api/platform/ops-server-categories/" + tmpId, admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        deleteJson("/api/platform/ops-servers/" + serverId, admin).andExpect(status().isNoContent());
    }

    // ---------- 服务器 category 收口：必须是受管标签 ----------

    @Test
    void servers_categoryMustBeManagedTag() throws Exception {
        String admin = adminLogin("managed");
        long orgId = createOrg(admin, "Osc Managed", "osc-managed");

        // 未受管标签 → 400；受管标签 → 200
        postJson("/api/platform/ops-servers",
                new CreateOpsServerRequest("osc-managed-x", "X", "10.0.0.1", null, null, null,
                        null, "osc-不存在", null, null, orgId, 0L, null), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        createCategory(admin, "osc-网关");
        long serverId = createServer(admin, "managed-a", orgId, "osc-网关");

        // 更新为未受管 → 400；更新为空串 = 清除标注（历史行兼容）→ 200
        putJson("/api/platform/ops-servers/" + serverId,
                new UpdateOpsServerRequest(null, null, null, null, null, null, "osc-不存在",
                        null, null, null, null, null), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        putJson("/api/platform/ops-servers/" + serverId,
                new UpdateOpsServerRequest(null, null, null, null, null, null, "",
                        null, null, null, null, null), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.category").value(""));
    }

    // ---------- spoke 下发携带 category ----------

    @Test
    void spokeDistribution_carriesCategory() throws Exception {
        String admin = adminLogin("spoke");
        String owner = createUserAndLogin("osc_spoke_owner", PW);
        long orgId = createOrg(owner, "Osc Spoke", "osc-spoke");
        String me = getJson("/api/me", owner).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long ownerId = om.readTree(me).get("user").get("id").asLong();
        String key = om.readTree(postJson("/api/orgs/" + orgId + "/appkeys",
                new CreateAppKeyRequest("osc-key", null, null), owner)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("plainKey").asText();

        createCategory(admin, "osc-应用");
        long serverId = createServer(admin, "spoke-a", orgId, "osc-应用");
        // 授权当前 appkey 用户（未授权不下发）
        postJson("/api/platform/ops-servers/" + serverId + "/grants",
                new CreateOpsServerGrantRequest(ownerId, Instant.now().plusSeconds(3600)), admin)
                .andExpect(status().isOk());

        String json = spokeGet("/api/spoke/ops-servers", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode row = findRow(json, "serverKey", "osc-spoke-a");
        assertThat(row).isNotNull();
        assertThat(row.get("category").asText()).isEqualTo("osc-应用");
    }
}
