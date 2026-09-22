package com.xinl.easyclaw.hub.spoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.featureflag.CreateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.featureflag.SetFlagEnabledRequest;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.SetMenuVisibleRequest;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * spoke 目录下发（appkey 认证）：GET /api/spoke/menus（生效树）、/api/spoke/feature-flags、
 * /api/spoke/tools（生效清单）。生效语义 = 平台 enabled AND 组织 visible/enabled；
 * 菜单父被过滤时子项提升为顶层；组织间开关行隔离。
 * 平台目录全局共享（跨用例持久），菜单/开关断言一律按本用例的 scd- 前缀过滤；
 * 工具目录固定 38 项播种，可直接断言长度。用户名统一 scd_ 前缀，slug 统一 scd- 前缀。
 */
class SpokeCatalogDistributionTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Env(String padmin, String owner, long orgId, String plainKey) {
    }

    /** 平台管理员 + 组织 + 一枚无绑定 appkey（下发接口不依赖 provider 绑定）。 */
    private Env newEnv(String tag) throws Exception {
        createPlatformAdminOk("scd_" + tag + "_padmin", PW);
        String padmin = loginOk("scd_" + tag + "_padmin", PW).accessToken();
        String owner = createUserAndLogin("scd_" + tag + "_owner", PW);
        long orgId = createOrg(owner, "Scd " + tag, "scd-" + tag);
        String json = postJson("/api/orgs/" + orgId + "/appkeys",
                new CreateAppKeyRequest("scd-key", null, null), owner)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Env(padmin, owner, orgId, om.readTree(json).get("plainKey").asText());
    }

    private long createOrg(String token, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createPlatformMenu(String padmin, String key, String label, String path, Long parentId,
                                    Integer sortOrder, Boolean enabled) throws Exception {
        String json = postJson("/api/platform/menus",
                new CreateMenuItemRequest(key, label, null, path, parentId, null, null, sortOrder, enabled), padmin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private ResultActions spokeGet(String url, String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + (appKey == null ? "" : appKey)));
    }

    /** 在清单 JSON 里按 key 字段找行（找不到返回 null）。 */
    private JsonNode findRow(String json, String keyField, String keyValue) throws Exception {
        for (JsonNode n : om.readTree(json)) {
            if (keyValue.equals(n.get(keyField).asText())) {
                return n;
            }
        }
        return null;
    }

    /** 收集清单 JSON 里 key 字段以 prefix 开头的行的 key 值（保序）。 */
    private List<String> keysWithPrefix(String json, String keyField, String prefix) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode n : om.readTree(json)) {
            String v = n.get(keyField).asText();
            if (v.startsWith(prefix)) {
                out.add(v);
            }
        }
        return out;
    }

    // ---------- appkey 认证 ----------

    @Test
    void distribution_requiresAppKey() throws Exception {
        Env e = newEnv("auth");
        for (String url : new String[]{"/api/spoke/menus", "/api/spoke/feature-flags", "/api/spoke/tools"}) {
            spokeGet(url, null)
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
            spokeGet(url, "eck-00000000000000000000000000000000")
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
            spokeGet(url, e.plainKey()).andExpect(status().isOk());
        }
    }

    // ---------- 菜单：生效语义 + 树构建 ----------

    @Test
    void menus_effectiveTree_platformAndOrgFilters() throws Exception {
        Env e = newEnv("tree");
        long a = createPlatformMenu(e.padmin(), "scd-t-a", "A", "/a", null, 10, null);
        createPlatformMenu(e.padmin(), "scd-t-a-child", "AC", "/a/c", a, 1, null);
        createPlatformMenu(e.padmin(), "scd-t-b", "B", "/b", null, 20, false);
        long c = createPlatformMenu(e.padmin(), "scd-t-c", "C", "/c", null, 30, null);
        // 组织隐藏 scd-t-c（org visible=false）
        putJson("/api/orgs/" + e.orgId() + "/menu-settings/" + c, new SetMenuVisibleRequest(false), e.owner())
                .andExpect(status().isNoContent());

        String tree = spokeGet("/api/spoke/menus", e.plainKey())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 顶层按 sort_order：仅 scd-t-a（scd-t-b 平台停用、scd-t-c 组织隐藏）；scd-t-a-child 挂在 scd-t-a 下
        assertThat(keysWithPrefix(tree, "menuKey", "scd-t-")).containsExactly("scd-t-a");
        JsonNode aNode = findRow(tree, "menuKey", "scd-t-a");
        assertThat(keysWithPrefix(aNode.get("children").toString(), "menuKey", "scd-t-"))
                .containsExactly("scd-t-a-child");
        assertThat(aNode.get("path").asText()).isEqualTo("/a");
        assertThat(aNode.get("children").get(0).get("path").asText()).isEqualTo("/a/c");
    }

    @Test
    void menus_platformDisabledParent_promotesChildren() throws Exception {
        Env e = newEnv("orphan");
        long a = createPlatformMenu(e.padmin(), "scd-o-a", "A", "/a", null, 10, null);
        createPlatformMenu(e.padmin(), "scd-o-a-child", "AC", "/a/c", a, 1, null);
        createPlatformMenu(e.padmin(), "scd-o-c", "C", "/c", null, 30, null);
        // 平台停用父项 scd-o-a → 其子 scd-o-a-child 提升为顶层（不随父消失）
        putJson("/api/platform/menus/" + a, new UpdateMenuItemRequest(null, null, null, null, null, null, false),
                e.padmin()).andExpect(status().isOk());

        String tree = spokeGet("/api/spoke/menus", e.plainKey())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(keysWithPrefix(tree, "menuKey", "scd-o-")).containsExactly("scd-o-a-child", "scd-o-c");
    }

    @Test
    void menus_isolatedByOrg() throws Exception {
        Env a = newEnv("ax");
        Env b = newEnv("bx");
        long x = createPlatformMenu(a.padmin(), "scd-x", "X", "/x", null, 10, null);
        createPlatformMenu(a.padmin(), "scd-y", "Y", "/y", null, 30, null);
        // 目录是平台级共享的：B 组织未隐藏任何项 → 同样看到 scd-x、scd-y；
        // 组织隔离体现在开关行互不影响（其他用例创建的菜单不在断言范围内）
        String treeB = spokeGet("/api/spoke/menus", b.plainKey()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(findRow(treeB, "menuKey", "scd-x")).isNotNull();
        assertThat(findRow(treeB, "menuKey", "scd-y")).isNotNull();
        // A 组织隐藏 scd-x 后只看到 scd-y，B 组织不受影响
        putJson("/api/orgs/" + a.orgId() + "/menu-settings/" + x, new SetMenuVisibleRequest(false), a.owner())
                .andExpect(status().isNoContent());
        String treeA = spokeGet("/api/spoke/menus", a.plainKey()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(findRow(treeA, "menuKey", "scd-x")).isNull();
        assertThat(findRow(treeA, "menuKey", "scd-y")).isNotNull();
        treeB = spokeGet("/api/spoke/menus", b.plainKey()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(findRow(treeB, "menuKey", "scd-x")).isNotNull();
        assertThat(findRow(treeB, "menuKey", "scd-y")).isNotNull();
    }

    // ---------- 功能开关：生效语义 ----------

    @Test
    void flags_effectiveSemantics() throws Exception {
        Env e = newEnv("flag");
        String j1 = postJson("/api/platform/flags", new CreateFeatureFlagRequest("scd-f1", "F1", null, 10, null),
                e.padmin())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long f1 = om.readTree(j1).get("id").asLong();
        postJson("/api/platform/flags", new CreateFeatureFlagRequest("scd-f2", "F2", null, 20, false), e.padmin())
                .andExpect(status().isOk());
        // 组织停用 scd-f1
        putJson("/api/orgs/" + e.orgId() + "/flag-settings/" + f1, new SetFlagEnabledRequest(false), e.owner())
                .andExpect(status().isNoContent());
        // 下发：scd-f2 平台停用 → 不出现；scd-f1 平台开 + 组织停 → enabled=false
        String flags = spokeGet("/api/spoke/feature-flags", e.plainKey())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(keysWithPrefix(flags, "flagKey", "scd-")).containsExactly("scd-f1");
        assertThat(findRow(flags, "flagKey", "scd-f1").get("enabled").asBoolean()).isFalse();
        assertThat(findRow(flags, "flagKey", "scd-f1").get("label").asText()).isEqualTo("F1");
        // 组织恢复启用 → enabled=true
        putJson("/api/orgs/" + e.orgId() + "/flag-settings/" + f1, new SetFlagEnabledRequest(true), e.owner())
                .andExpect(status().isNoContent());
        flags = spokeGet("/api/spoke/feature-flags", e.plainKey())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(flags, "flagKey", "scd-f1").get("enabled").asBoolean()).isTrue();
    }

    // ---------- 工具：生效语义 ----------

    @Test
    void tools_effectiveSemantics() throws Exception {
        Env e = newEnv("tool");
        // 38 项内置工具播种；默认全启用
        String list = spokeGet("/api/spoke/tools", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(38))
                .andExpect(jsonPath("$[0].toolKey").value("read_file"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        // 组织停用 read_file（id 取自组织侧清单的 toolId 字段）→ 仍在清单中但 enabled=false
        long readFileId = findRow(getJson("/api/orgs/" + e.orgId() + "/tool-settings", e.owner())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "toolKey", "read_file").get("toolId").asLong();
        putJson("/api/orgs/" + e.orgId() + "/tool-settings/" + readFileId, new SetToolEnabledRequest(false), e.owner())
                .andExpect(status().isNoContent());
        String afterOrg = spokeGet("/api/spoke/tools", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(38))
                .andReturn().getResponse().getContentAsString();
        assertThat(findRow(afterOrg, "toolKey", "read_file").get("enabled").asBoolean()).isFalse();
        // 平台停用 write_file（id 取自平台目录）→ 从下发清单消失
        long writeId = findRow(getJson("/api/platform/tools", e.padmin())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "toolKey", "write_file").get("id").asLong();
        putJson("/api/platform/tools/" + writeId + "/enabled", new SetToolEnabledRequest(false), e.padmin())
                .andExpect(status().isOk());
        spokeGet("/api/spoke/tools", e.plainKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(37))
                .andExpect(jsonPath("$[*].toolKey", not(contains("write_file"))));
    }
}
