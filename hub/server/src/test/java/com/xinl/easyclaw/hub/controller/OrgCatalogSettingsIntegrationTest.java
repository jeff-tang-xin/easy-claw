package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.featureflag.CreateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.featureflag.SetFlagEnabledRequest;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.SetMenuVisibleRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织侧目录开关（菜单可见性 / 功能开关启用 / 工具启用）：
 * 读=组织成员（含 guest），写=owner/admin；惰性行语义（无行 = 默认可见/启用）；
 * 目录项不存在 → 404。目录 CRUD 本身见 PlatformCatalogIntegrationTest。
 * 平台目录全局共享（跨用例持久），断言一律按本用例的 key 前缀过滤，不断言清单总长度。
 * 用户名统一 os_ 前缀，slug 统一 os- 前缀；断言稳定错误码，不断言中文文案。
 */
class OrgCatalogSettingsIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Fixture(String platformAdmin, String owner, String admin, String member, String guest,
                           String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        createPlatformAdminOk("os_" + tag + "_padmin", PW);
        String padmin = loginOk("os_" + tag + "_padmin", PW).accessToken();
        String owner = createUserAndLogin("os_" + tag + "_owner", PW);
        String admin = createUserAndLogin("os_" + tag + "_admin", PW);
        String member = createUserAndLogin("os_" + tag + "_member", PW);
        String guest = createUserAndLogin("os_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("os_" + tag + "_out", PW);
        long orgId = createOrg(owner, "Os " + tag, "os-" + tag);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("os_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("os_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("os_" + tag + "_guest", "guest"), owner)
                .andExpect(status().isCreated());
        return new Fixture(padmin, owner, admin, member, guest, outsider, orgId);
    }

    private long createOrg(String token, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createPlatformMenu(String padmin, String key, String label, int sortOrder) throws Exception {
        String json = postJson("/api/platform/menus", new CreateMenuItemRequest(key, label, null, null, null, null,
                null, sortOrder, null), padmin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
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

    // ---------- 菜单可见性 ----------

    @Test
    void menuSettings_readMembers_writeOwners() throws Exception {
        Fixture f = newFixture("ms");
        long a = createPlatformMenu(f.platformAdmin(), "os-a", "A", 10);
        long b = createPlatformMenu(f.platformAdmin(), "os-b", "B", 20);
        // 读：member/guest 可读全量目录 × 默认可见；组织外 → 403
        String list = getJson("/api/orgs/" + f.orgId() + "/menu-settings", f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(list, "menuKey", "os-a").get("visible").asBoolean()).isTrue();
        getJson("/api/orgs/" + f.orgId() + "/menu-settings", f.guest()).andExpect(status().isOk());
        getJson("/api/orgs/" + f.orgId() + "/menu-settings", f.outsider())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 写：member/guest/outsider → 403
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + a, new SetMenuVisibleRequest(false), f.member())
                .andExpect(status().isForbidden());
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + a, new SetMenuVisibleRequest(false), f.guest())
                .andExpect(status().isForbidden());
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + a, new SetMenuVisibleRequest(false), f.outsider())
                .andExpect(status().isForbidden());
        // 写：owner/admin → 204，生效态翻转
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + a, new SetMenuVisibleRequest(false), f.owner())
                .andExpect(status().isNoContent());
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + b, new SetMenuVisibleRequest(false), f.admin())
                .andExpect(status().isNoContent());
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/" + b, new SetMenuVisibleRequest(true), f.owner())
                .andExpect(status().isNoContent());
        String after = getJson("/api/orgs/" + f.orgId() + "/menu-settings", f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(after, "menuKey", "os-a").get("visible").asBoolean()).isFalse();
        assertThat(findRow(after, "menuKey", "os-b").get("visible").asBoolean()).isTrue();
        // 目录项不存在 → 404
        putJson("/api/orgs/" + f.orgId() + "/menu-settings/999999", new SetMenuVisibleRequest(false), f.owner())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- 功能开关启用 ----------

    @Test
    void flagSettings_toggleAndDefaultOn() throws Exception {
        Fixture f = newFixture("fs");
        String json = postJson("/api/platform/flags", new CreateFeatureFlagRequest("os-flag", "实验开关", null, 10, null),
                f.platformAdmin())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long flagId = om.readTree(json).get("id").asLong();
        // 默认启用（无开关行）
        String list = getJson("/api/orgs/" + f.orgId() + "/flag-settings", f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(list, "flagKey", "os-flag").get("enabled").asBoolean()).isTrue();
        // 写权限：member → 403；owner → 204
        putJson("/api/orgs/" + f.orgId() + "/flag-settings/" + flagId, new SetFlagEnabledRequest(false), f.member())
                .andExpect(status().isForbidden());
        putJson("/api/orgs/" + f.orgId() + "/flag-settings/" + flagId, new SetFlagEnabledRequest(false), f.owner())
                .andExpect(status().isNoContent());
        String after = getJson("/api/orgs/" + f.orgId() + "/flag-settings", f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(after, "flagKey", "os-flag").get("enabled").asBoolean()).isFalse();
        // 幂等重复设置 + 恢复
        putJson("/api/orgs/" + f.orgId() + "/flag-settings/" + flagId, new SetFlagEnabledRequest(false), f.owner())
                .andExpect(status().isNoContent());
        putJson("/api/orgs/" + f.orgId() + "/flag-settings/" + flagId, new SetFlagEnabledRequest(true), f.owner())
                .andExpect(status().isNoContent());
        // 目录项不存在 → 404
        putJson("/api/orgs/" + f.orgId() + "/flag-settings/999999", new SetFlagEnabledRequest(false), f.owner())
                .andExpect(status().isNotFound());
    }

    // ---------- 工具启用 ----------

    @Test
    void toolSettings_toggleAndDefaultOn() throws Exception {
        Fixture f = newFixture("ts");
        // 38 项内置工具由 ToolCatalogBootstrap 启动播种，组织侧默认全启用
        String list = getJson("/api/orgs/" + f.orgId() + "/tool-settings", f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(38))
                .andExpect(jsonPath("$[0].toolKey").value("read_file"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        long readFileId = findRow(list, "toolKey", "read_file").get("toolId").asLong();
        // 写权限：guest → 403；owner → 204
        putJson("/api/orgs/" + f.orgId() + "/tool-settings/" + readFileId, new SetToolEnabledRequest(false), f.guest())
                .andExpect(status().isForbidden());
        putJson("/api/orgs/" + f.orgId() + "/tool-settings/" + readFileId, new SetToolEnabledRequest(false), f.owner())
                .andExpect(status().isNoContent());
        String after = getJson("/api/orgs/" + f.orgId() + "/tool-settings", f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(findRow(after, "toolKey", "read_file").get("enabled").asBoolean()).isFalse();
        assertThat(findRow(after, "toolKey", "write_file").get("enabled").asBoolean()).isTrue();
        // 目录项不存在 → 404
        putJson("/api/orgs/" + f.orgId() + "/tool-settings/999999", new SetToolEnabledRequest(false), f.owner())
                .andExpect(status().isNotFound());
    }
}
