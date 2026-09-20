package com.xinl.easyclaw.hub.spoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.workspace.CreateWorkspaceRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * spoke 配置下发：appkey 认证拉取本组织 active 工作区与菜单树（仅 enabled、按 sort_order 保序、
 * 父禁用时子项提升为顶层），跨组织工作区 404，归档工作区不下发。
 * 用户名统一 swd_ 前缀，slug 统一 swd- 前缀。
 */
class SpokeWorkspaceDistributionTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Env(String ownerToken, long orgId, String plainKey) {
    }

    /** 组织 + 一个带菜单的工作区 + 一枚无绑定 appkey（下发接口不依赖 provider 绑定）。 */
    private Env newEnv(String tag) throws Exception {
        String owner = createUserAndLogin("swd_" + tag + "_owner", PW);
        long orgId = createOrg(owner, "Swd " + tag, "swd-" + tag);
        JsonNode created = createAppKeyRaw(owner, orgId);
        return new Env(owner, orgId, created.get("plainKey").asText());
    }

    private long createOrg(String token, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private JsonNode createAppKeyRaw(String ownerToken, long orgId) throws Exception {
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                new CreateAppKeyRequest("swd-key", null, null), ownerToken)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    /** 建 project→workspace→菜单（a 顶层 sort10、a-child sort1、b 顶层 sort20 停用、c 顶层 sort30）。 */
    private long createWorkspaceWithMenu(String owner, long orgId, String slug, String wsName) throws Exception {
        String pj = postJson("/api/projects", new CreateProjectRequest(orgId, slug, "N " + slug, null, null), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long pid = om.readTree(pj).get("id").asLong();
        String wj = postJson("/api/workspaces", new CreateWorkspaceRequest(pid, wsName), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long wid = om.readTree(wj).get("id").asLong();
        String aj = postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest("a", "A", null, "/a", null, null, null, 10, null), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long aId = om.readTree(aj).get("id").asLong();
        postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest("a-child", "AC", null, "/a/c", aId, null, null, 1, null), owner)
                .andExpect(status().isOk());
        postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest("b", "B", null, "/b", null, null, null, 20, false), owner)
                .andExpect(status().isOk());
        postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest("c", "C", null, "/c", null, null, null, 30, null), owner)
                .andExpect(status().isOk());
        return wid;
    }

    private org.springframework.test.web.servlet.ResultActions spokeGet(String url, String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + (appKey == null ? "" : appKey)));
    }

    @Test
    void distribution_requiresAppKey() throws Exception {
        Env e = newEnv("auth");
        spokeGet("/api/spoke/workspaces", null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        spokeGet("/api/spoke/workspaces", "eck-00000000000000000000000000000000")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        spokeGet("/api/spoke/workspaces", e.plainKey()).andExpect(status().isOk());
    }

    @Test
    void workspaces_returnsEnabledMenuTree() throws Exception {
        Env e = newEnv("tree");
        long wid = createWorkspaceWithMenu(e.ownerToken(), e.orgId(), "swd-tree-p1", "Tree WS");

        spokeGet("/api/spoke/workspaces", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) wid))
                .andExpect(jsonPath("$[0].name").value("Tree WS"))
                .andExpect(jsonPath("$[0].orgId").value((int) e.orgId()))
                // 顶层按 sort_order：a(10) → c(30)；停用的 b 不下发；a-child 挂在 a 下
                .andExpect(jsonPath("$[0].menu[*].menuKey", contains("a", "c")))
                .andExpect(jsonPath("$[0].menu[0].children[*].menuKey", contains("a-child")))
                .andExpect(jsonPath("$[0].menu[0].path").value("/a"))
                .andExpect(jsonPath("$[0].menu[0].children[0].path").value("/a/c"));

        spokeGet("/api/spoke/workspaces/" + wid + "/menu", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].menuKey", contains("a", "c")));
    }

    @Test
    void workspaces_excludesArchived_andCrossOrgMenuIs404() throws Exception {
        Env a = newEnv("ax");
        Env b = newEnv("bx");
        long wa = createWorkspaceWithMenu(a.ownerToken(), a.orgId(), "swd-ax-p1", "WS-A");
        long wb = createWorkspaceWithMenu(b.ownerToken(), b.orgId(), "swd-bx-p1", "WS-B");

        // 跨组织：A 的 key 取 B 工作区菜单 → 404（不泄露存在性）
        spokeGet("/api/spoke/workspaces/" + wb + "/menu", a.plainKey())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // B 的下发只含 B 的工作区
        spokeGet("/api/spoke/workspaces", b.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) wb));

        // 归档后不再下发
        deleteJson("/api/workspaces/" + wa, a.ownerToken()).andExpect(status().isNoContent());
        spokeGet("/api/spoke/workspaces", a.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void disabledParent_promotesChildrenToTopLevel() throws Exception {
        Env e = newEnv("orphan");
        long wid = createWorkspaceWithMenu(e.ownerToken(), e.orgId(), "swd-or-p1", "Orphan WS");
        // 停用父项 a → 其子 a-child 提升为顶层（不随父消失）
        String list = getJson("/api/workspaces/" + wid + "/menus", e.ownerToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long aId = 0;
        for (JsonNode n : om.readTree(list)) {
            if ("a".equals(n.get("menuKey").asText())) {
                aId = n.get("id").asLong();
            }
        }
        postJson("/api/menus/" + aId + "/toggle?enabled=false", null, e.ownerToken())
                .andExpect(status().isOk());

        spokeGet("/api/spoke/workspaces/" + wid + "/menu", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].menuKey", contains("a-child", "c")));
    }
}
