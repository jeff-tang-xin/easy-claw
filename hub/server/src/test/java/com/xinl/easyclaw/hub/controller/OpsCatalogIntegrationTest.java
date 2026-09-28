//package com.xinl.easyclaw.hub.controller;
//
//import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerRequest;
//import com.xinl.easyclaw.hub.contract.ops.CreateShellCommandRequest;
//import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerRequest;
//import com.xinl.easyclaw.hub.contract.ops.UpdateShellCommandRequest;
//import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
//import org.junit.jupiter.api.Test;
//
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
///**
// * 运维平台目录管理面（/api/platform/ops-servers、/api/platform/shell-commands，仅 platformAdmin）：
// * serverKey/cmd 全局唯一且创建后不可改、port 缺省 22、可空文本归一化空串、
// * subcommands 去重保序（空 = 整命令放行）。下发侧（/api/spoke/**）见 SpokeOpsKnowledgeBlackboardTest。
// * 用户名统一 oct_ 前缀，serverKey/cmd 统一 oct- 前缀（目录全局共享，跨用例持久）；
// * 断言稳定错误码，不断言中文文案。
// */
//class OpsCatalogIntegrationTest extends HubIntegrationTestSupport {
//
//    private static final String PW = "password123";
//
//    private String adminLogin(String tag) throws Exception {
//        createPlatformAdminOk("oct_" + tag + "_admin", PW);
//        return loginOk("oct_" + tag + "_admin", PW).accessToken();
//    }
//
//    private String userLogin(String tag) throws Exception {
//        createUserAndLogin("oct_" + tag + "_user", PW);
//        return loginOk("oct_" + tag + "_user", PW).accessToken();
//    }
//
//    // ---------- 平台管理员门槛 ----------
//
//    @Test
//    void opsCatalogEndpoints_requirePlatformAdmin() throws Exception {
//        String admin = adminLogin("gate");
//        String user = userLogin("gate");
//        // 非平台管理员：读写一律 403（id 不存在也先撞 403）
//        getJson("/api/platform/ops-servers", user).andExpect(status().isForbidden())
//                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
//        postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("oct-gate", "G", "h", null, null, null, null, null), user)
//                .andExpect(status().isForbidden());
//        getJson("/api/platform/shell-commands", user).andExpect(status().isForbidden());
//        putJson("/api/platform/shell-commands/999999",
//                new UpdateShellCommandRequest(null, null, null), user).andExpect(status().isForbidden());
//        // 平台管理员：目录可读（全局共享，不断言数量）
//        getJson("/api/platform/ops-servers", admin).andExpect(status().isOk());
//        getJson("/api/platform/shell-commands", admin).andExpect(status().isOk());
//    }
//
//    // ---------- 运维服务器目录 ----------
//
//    @Test
//    void opsServers_crud_uniqueKeyAndImmutableServerKey() throws Exception {
//        String admin = adminLogin("srv");
//        // 创建：port 缺省 22、可空字段归一化空串
//        String json = postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("oct-srv-a", "主机 A", "10.0.0.1", null, null, null, 5, null), admin)
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.serverKey").value("oct-srv-a"))
//                .andExpect(jsonPath("$.host").value("10.0.0.1"))
//                .andExpect(jsonPath("$.port").value(22))
//                .andExpect(jsonPath("$.username").value(""))
//                .andExpect(jsonPath("$.description").value(""))
//                .andExpect(jsonPath("$.sortOrder").value(5))
//                .andExpect(jsonPath("$.enabled").value(true))
//                .andReturn().getResponse().getContentAsString();
//        long id = om.readTree(json).get("id").asLong();
//        // serverKey 全局唯一 → 409；非法字符 → 400（@Pattern）
//        postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("oct-srv-a", "Dup", "h2", null, null, null, null, null), admin)
//                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
//        postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("Bad Key!", "X", "h", null, null, null, null, null), admin)
//                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
//        // 更新：host/port/username/enabled 可改；serverKey 不在请求体中（创建后不可改）
//        putJson("/api/platform/ops-servers/" + id,
//                new UpdateOpsServerRequest("主机 A2", "10.0.0.2", 2222, "root", "备注", null, false), admin)
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.serverKey").value("oct-srv-a"))
//                .andExpect(jsonPath("$.host").value("10.0.0.2"))
//                .andExpect(jsonPath("$.port").value(2222))
//                .andExpect(jsonPath("$.username").value("root"))
//                .andExpect(jsonPath("$.enabled").value(false));
//        // 删除 → 列表不再含；再删 → 404
//        deleteJson("/api/platform/ops-servers/" + id, admin).andExpect(status().isNoContent());
//        Set<String> keys = new HashSet<>();
//        for (var n : om.readTree(getJson("/api/platform/ops-servers", admin)
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())) {
//            keys.add(n.get("serverKey").asText());
//        }
//        assertThat(keys).doesNotContain("oct-srv-a");
//        deleteJson("/api/platform/ops-servers/999999", admin)
//                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
//    }
//
//    // ---------- Shell 命令白名单目录 ----------
//
//    @Test
//    void shellCommands_crud_subcommandsDedupAndReplace() throws Exception {
//        String admin = adminLogin("cmd");
//        // 创建：subcommands 去重保序、空白项剔除
//        String json = postJson("/api/platform/shell-commands",
//                new CreateShellCommandRequest("oct-cmd-systemctl", List.of("status", " status ", "", "status"), 3, null),
//                admin)
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.cmd").value("oct-cmd-systemctl"))
//                .andExpect(jsonPath("$.subcommands.length()").value(1))
//                .andExpect(jsonPath("$.subcommands[0]").value("status"))
//                .andExpect(jsonPath("$.sortOrder").value(3))
//                .andExpect(jsonPath("$.enabled").value(true))
//                .andReturn().getResponse().getContentAsString();
//        long id = om.readTree(json).get("id").asLong();
//        // cmd 全局唯一 → 409
//        postJson("/api/platform/shell-commands",
//                new CreateShellCommandRequest("oct-cmd-systemctl", null, null, null), admin)
//                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
//        // subcommands 传 null = 不改；传列表 = 整体替换；空列表 = 清空（整命令放行）
//        putJson("/api/platform/shell-commands/" + id,
//                new UpdateShellCommandRequest(null, 9, null), admin)
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.cmd").value("oct-cmd-systemctl"))
//                .andExpect(jsonPath("$.subcommands.length()").value(1))
//                .andExpect(jsonPath("$.sortOrder").value(9));
//        putJson("/api/platform/shell-commands/" + id,
//                new UpdateShellCommandRequest(List.of(), null, true), admin)
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.subcommands.length()").value(0))
//                .andExpect(jsonPath("$.enabled").value(true));
//        // 删除 → 404 复删
//        deleteJson("/api/platform/shell-commands/" + id, admin).andExpect(status().isNoContent());
//        deleteJson("/api/platform/shell-commands/999999", admin)
//                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
//    }
//}
