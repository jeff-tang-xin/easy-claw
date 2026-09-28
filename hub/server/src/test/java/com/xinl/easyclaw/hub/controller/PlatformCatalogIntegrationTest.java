package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.featureflag.CreateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.featureflag.UpdateFeatureFlagRequest;
import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台目录管理面（/api/platform/**，仅 platformAdmin）：
 * 菜单/功能开关 CRUD（key 派生/唯一/校验、级联删除）与工具目录（只读 + 平台总开关，
 * 38 项内置工具由 ToolCatalogBootstrap 启动播种）。组织侧开关见 OrgCatalogSettingsIntegrationTest。
 * 用户名统一 pc_ 前缀，slug 统一 pc- 前缀；断言稳定错误码，不断言中文文案。
 */
class PlatformCatalogIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private String adminLogin(String tag) throws Exception {
        createPlatformAdminOk("pc_" + tag + "_admin", PW);
        return loginOk("pc_" + tag + "_admin", PW).accessToken();
    }

    private String userLogin(String tag) throws Exception {
        createUserAndLogin("pc_" + tag + "_user", PW);
        return loginOk("pc_" + tag + "_user", PW).accessToken();
    }

    private static CreateMenuItemRequest menu(String key, String label) {
        return new CreateMenuItemRequest(key, label, null, null, null, null, null, null, null);
    }

    // ---------- 平台管理员门槛 ----------

    @Test
    void platformEndpoints_requirePlatformAdmin() throws Exception {
        String admin = adminLogin("gate");
        String user = userLogin("gate");
        // 非平台管理员：读写一律 403（含工具总开关，id 不存在也先撞 403）
        getJson("/api/platform/menus", user).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/platform/menus", menu("x", "X"), user).andExpect(status().isForbidden());
        getJson("/api/platform/flags", user).andExpect(status().isForbidden());
        getJson("/api/platform/tools", user).andExpect(status().isForbidden());
        putJson("/api/platform/tools/999999/enabled", new SetToolEnabledRequest(false), user)
                .andExpect(status().isForbidden());
        // 平台管理员：目录可读（目录全局共享，其他用例可能已写入条目，不断言数量）
        getJson("/api/platform/menus", admin).andExpect(status().isOk());
        getJson("/api/platform/flags", admin).andExpect(status().isOk());
    }

    // ---------- 菜单目录 ----------

    @Test
    void menus_crud_keyDerivationAndValidation() throws Exception {
        String admin = adminLogin("mk");
        // 显式 key + 全字段
        String json = postJson("/api/platform/menus",
                new CreateMenuItemRequest("console", "Console", "grid", "/console", null, "llm.invoke", "owner,admin",
                        5, true), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuKey").value("console"))
                .andExpect(jsonPath("$.requiredPerm").value("llm.invoke"))
                .andExpect(jsonPath("$.visibleRoles").value("owner,admin"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        long consoleId = om.readTree(json).get("id").asLong();
        // 子项挂父
        postJson("/api/platform/menus",
                new CreateMenuItemRequest("console-users", "Users", null, null, consoleId, null, null, null, null),
                admin).andExpect(status().isOk());
        // key 留空：英文 label 派生 slug
        postJson("/api/platform/menus", menu(null, "Home Page"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("home-page"));
        // 纯中文 label 兜底 menu，冲突自动加后缀
        postJson("/api/platform/menus", menu(null, "工作台"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("menu"));
        postJson("/api/platform/menus", menu(null, "报表"), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("menu-2"));
        // key 全局冲突 → 409；非法字符 → 400
        postJson("/api/platform/menus", menu("console", "Dup"), admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        postJson("/api/platform/menus", menu("Bad Key!", "X"), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 更新：label/sortOrder/enabled（menuKey 不在请求体中，创建后不可改）
        putJson("/api/platform/menus/" + consoleId,
                new UpdateMenuItemRequest("控制台", null, "/console/v2", null, null, 1, false), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuKey").value("console"))
                .andExpect(jsonPath("$.label").value("控制台"))
                .andExpect(jsonPath("$.path").value("/console/v2"))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.enabled").value(false));
        // 删除父项 → 级联删除子项
        deleteJson("/api/platform/menus/" + consoleId, admin).andExpect(status().isNoContent());
        // 目录全局共享（其他用例也会写入条目），收集 key 断言本用例的创建/删除结果
        Set<String> menuKeys = new HashSet<>();
        for (JsonNode n : om.readTree(getJson("/api/platform/menus", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())) {
            menuKeys.add(n.get("menuKey").asText());
        }
        assertThat(menuKeys).contains("home-page", "menu", "menu-2").doesNotContain("console", "console-users");
        // 删除不存在的项 → 404
        deleteJson("/api/platform/menus/999999", admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- 功能开关目录 ----------

    @Test
    void flags_crud_keyDerivationAndConflict() throws Exception {
        String admin = adminLogin("fk");
        // 显式 key
        String json = postJson("/api/platform/flags",
                new CreateFeatureFlagRequest("beta-export", "Beta 导出", "灰度功能", 15, null), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("beta-export"))
                .andExpect(jsonPath("$.label").value("Beta 导出"))
                .andExpect(jsonPath("$.description").value("灰度功能"))
                .andExpect(jsonPath("$.sortOrder").value(15))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(json).get("id").asLong();
        // key 冲突 → 409；key 留空由 label 派生（纯中文兜底 flag，冲突加后缀）
        postJson("/api/platform/flags", new CreateFeatureFlagRequest("beta-export", "Dup", null, null, null), admin)
                .andExpect(status().isConflict());
        postJson("/api/platform/flags", new CreateFeatureFlagRequest(null, "实验开关", null, null, null), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.flagKey").value("flag"));
        postJson("/api/platform/flags", new CreateFeatureFlagRequest(null, "另一个实验", null, null, null), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.flagKey").value("flag-2"));
        // 更新：label/description/enabled（flagKey 不在请求体中，创建后不可改）
        putJson("/api/platform/flags/" + id,
                new UpdateFeatureFlagRequest("正式导出", null, null, false), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("beta-export"))
                .andExpect(jsonPath("$.label").value("正式导出"))
                .andExpect(jsonPath("$.enabled").value(false));
        // 删除 → 列表不再含（目录全局共享，收集 key 断言本用例的创建/删除结果）
        deleteJson("/api/platform/flags/" + id, admin).andExpect(status().isNoContent());
        Set<String> flagKeys = new HashSet<>();
        for (JsonNode n : om.readTree(getJson("/api/platform/flags", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())) {
            flagKeys.add(n.get("flagKey").asText());
        }
        assertThat(flagKeys).contains("flag", "flag-2").doesNotContain("beta-export");
        deleteJson("/api/platform/flags/999999", admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------- 工具目录（播种 + 平台总开关） ----------

    @Test
    void tools_seededCatalogAndPlatformToggle() throws Exception {
        String admin = adminLogin("tk");
        // ToolCatalogBootstrap 启动播种 38 项（22 框架 + 16 自定义），按 sort_order 保序
        String list = getJson("/api/platform/tools", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(38))
                .andExpect(jsonPath("$[0].toolKey").value("read_file"))
                .andExpect(jsonPath("$[0].displayName").value("读取文件"))
                .andExpect(jsonPath("$[0].toolGroup").value("文件"))
                .andReturn().getResponse().getContentAsString();
        long readFileId = om.readTree(list).get(0).get("id").asLong();
        // 平台总开关：关 → 开（幂等往返）
        putJson("/api/platform/tools/" + readFileId + "/enabled", new SetToolEnabledRequest(false), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolKey").value("read_file"))
                .andExpect(jsonPath("$.enabled").value(false));
        putJson("/api/platform/tools/" + readFileId + "/enabled", new SetToolEnabledRequest(true), admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        // 工具目录不可增删（无 POST/DELETE 端点）——只读清单 + 总开关
        putJson("/api/platform/tools/999999/enabled", new SetToolEnabledRequest(false), admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
