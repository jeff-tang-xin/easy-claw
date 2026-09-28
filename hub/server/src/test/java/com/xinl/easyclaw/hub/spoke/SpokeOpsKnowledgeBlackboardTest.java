//package com.xinl.easyclaw.hub.spoke;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
//import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerRequest;
//import com.xinl.easyclaw.hub.contract.ops.CreateShellCommandRequest;
//import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerRequest;
//import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
//import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardAppendRequest;
//import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardArchiveRequest;
//import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeUpsertRequest;
//import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
//import org.junit.jupiter.api.Test;
//import org.springframework.test.web.servlet.ResultActions;
//import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
///**
// * spoke 运维下发与工作区云同步（appkey 认证）：
// * GET /api/spoke/ops-servers、/api/spoke/shell-commands（仅启用项，sort_order,id 保序，服务器不含任何凭证）；
// * 工作区知识库 /api/spoke/knowledge/*（(org,workspaceId,topic) 唯一，upsert 整体覆盖，组织隔离）；
// * 工作区黑板 /api/spoke/blackboard/*（append-only，seq 服务端分配，归档本拒绝追加，
// * 归档 = 整本 book_key 改 &lt;key&gt;.archived-&lt;epochMillis&gt;）。
// * 用户名统一 osk_ 前缀，serverKey/cmd 统一 osk- 前缀（平台目录全局共享，跨用例持久，按前缀过滤）。
// */
//class SpokeOpsKnowledgeBlackboardTest extends HubIntegrationTestSupport {
//
//    private static final String PW = "password123";
//
//    private record Env(String padmin, String owner, long orgId, String plainKey) {
//    }
//
//    /** 平台管理员 + 组织 + 一枚无绑定 appkey。 */
//    private Env newEnv(String tag) throws Exception {
//        createPlatformAdminOk("osk_" + tag + "_padmin", PW);
//        String padmin = loginOk("osk_" + tag + "_padmin", PW).accessToken();
//        String owner = createUserAndLogin("osk_" + tag + "_owner", PW);
//        long orgId = createOrg(owner, "Osk " + tag, "osk-" + tag);
//        String json = postJson("/api/orgs/" + orgId + "/appkeys",
//                new CreateAppKeyRequest("osk-key", null, null), owner)
//                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
//        return new Env(padmin, owner, orgId, om.readTree(json).get("plainKey").asText());
//    }
//
//    private long createOrg(String token, String name, String slug) throws Exception {
//        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        return om.readTree(json).get("id").asLong();
//    }
//
//    private ResultActions spokeGet(String url, String appKey) throws Exception {
//        return mvc.perform(MockMvcRequestBuilders.get(url)
//                .header("Authorization", "Bearer " + (appKey == null ? "" : appKey)));
//    }
//
//    private ResultActions spokePost(String url, Object body, String appKey) throws Exception {
//        return mvc.perform(MockMvcRequestBuilders.post(url)
//                .header("Authorization", "Bearer " + appKey)
//                .contentType("application/json")
//                .content(om.writeValueAsString(body)));
//    }
//
//    /** 在清单 JSON 里按 key 字段找以 prefix 开头的行的指定字段值（保序）。 */
//    private List<String> valuesWithPrefix(String json, String keyField, String prefix) throws Exception {
//        List<String> out = new ArrayList<>();
//        for (JsonNode n : om.readTree(json)) {
//            String v = n.get(keyField).asText();
//            if (v.startsWith(prefix)) {
//                out.add(v);
//            }
//        }
//        return out;
//    }
//
//    private JsonNode findRow(String json, String keyField, String keyValue) throws Exception {
//        for (JsonNode n : om.readTree(json)) {
//            if (keyValue.equals(n.get(keyField).asText())) {
//                return n;
//            }
//        }
//        return null;
//    }
//
//    // ---------- appkey 认证 ----------
//
//    @Test
//    void opsAndWorkspaceEndpoints_requireAppKey() throws Exception {
//        Env e = newEnv("auth");
//        for (String url : new String[]{"/api/spoke/ops-servers", "/api/spoke/shell-commands",
//                "/api/spoke/knowledge/entries?workspaceId=ws-x", "/api/spoke/blackboard/books?workspaceId=ws-x"}) {
//            spokeGet(url, null)
//                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
//            spokeGet(url, "eck-00000000000000000000000000000000")
//                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
//            spokeGet(url, e.plainKey()).andExpect(status().isOk());
//        }
//    }
//
//    // ---------- 运维服务器 / Shell 白名单下发 ----------
//
//    @Test
//    void opsServers_distribution_enabledOnlyNoCredentials() throws Exception {
//        Env e = newEnv("ops");
//        String on = postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("osk-ops-on", "启用机", "10.1.0.1", 2202, "root", "第一台", 10, true), e.padmin())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        String off = postJson("/api/platform/ops-servers",
//                new CreateOpsServerRequest("osk-ops-off", "停用机", "10.1.0.2", null, null, null, 20, false), e.padmin())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        long onId = om.readTree(on).get("id").asLong();
//        // 平台停用第一台 → 下发消失；重新启用 → 恢复
//        var row = findRow(spokeGet("/api/spoke/ops-servers", e.plainKey()).andExpect(status().isOk())
//                .andReturn().getResponse().getContentAsString(), "serverKey", "osk-ops-on");
//        assertThat(row).isNotNull();
//        assertThat(row.get("host").asText()).isEqualTo("10.1.0.1");
//        assertThat(row.get("port").asInt()).isEqualTo(2202);
//        // 凭证红线：下发视图不含任何凭证字段
//        assertThat(row.has("password")).isFalse();
//        assertThat(row.has("privateKey")).isFalse();
//        assertThat(valuesWithPrefix(spokeGet("/api/spoke/ops-servers", e.plainKey())
//                .andReturn().getResponse().getContentAsString(), "serverKey", "osk-ops-"))
//                .containsExactly("osk-ops-on");
//        // 平台停用 → 消失
//        putJson("/api/platform/ops-servers/" + onId,
//                new UpdateOpsServerRequest(null, null, null, null, null, null, false), e.padmin())
//                .andExpect(status().isOk());
//        assertThat(valuesWithPrefix(spokeGet("/api/spoke/ops-servers", e.plainKey())
//                .andReturn().getResponse().getContentAsString(), "serverKey", "osk-ops-"))
//                .isEmpty();
//        assertThat(om.readTree(off).get("enabled").asBoolean()).isFalse();
//    }
//
//    @Test
//    void shellCommands_distribution_enabledOnlyWithSubcommands() throws Exception {
//        Env e = newEnv("sh");
//        postJson("/api/platform/shell-commands",
//                new CreateShellCommandRequest("osk-sh-systemctl", List.of("status", "list-units"), 10, true), e.padmin())
//                .andExpect(status().isOk());
//        postJson("/api/platform/shell-commands",
//                new CreateShellCommandRequest("osk-sh-reboot", null, 20, false), e.padmin())
//                .andExpect(status().isOk());
//        String json = spokeGet("/api/spoke/shell-commands", e.plainKey())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        // 仅启用项；subcommands 逗号串还原为列表
//        assertThat(valuesWithPrefix(json, "cmd", "osk-sh-")).containsExactly("osk-sh-systemctl");
//        JsonNode row = findRow(json, "cmd", "osk-sh-systemctl");
//        assertThat(row.get("subcommands").toString()).contains("status").contains("list-units");
//    }
//
//    // ---------- 工作区知识库云同步 ----------
//
//    @Test
//    void knowledge_upsertOverwriteAndOrgIsolation() throws Exception {
//        Env a = newEnv("ka");
//        Env b = newEnv("kb");
//        String ws = "ws-osk-knowledge";
//        // 创建
//        spokePost("/api/spoke/knowledge/entries",
//                new SpokeKnowledgeUpsertRequest(ws, "osk-topic", "摘要 v1", "内容 v1"), a.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.topic").value("osk-topic"))
//                .andExpect(jsonPath("$.summary").value("摘要 v1"));
//        // 读取
//        spokeGet("/api/spoke/knowledge/entry?workspaceId=" + ws + "&topic=osk-topic", a.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.content").value("内容 v1"));
//        // upsert 整体覆盖
//        spokePost("/api/spoke/knowledge/entries",
//                new SpokeKnowledgeUpsertRequest(ws, "osk-topic", "摘要 v2", "内容 v2 更长了"), a.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.summary").value("摘要 v2"))
//                .andExpect(jsonPath("$.fileSize").value("内容 v2 更长了".getBytes().length));
//        spokeGet("/api/spoke/knowledge/entry?workspaceId=" + ws + "&topic=osk-topic", a.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.content").value("内容 v2 更长了"));
//        // 清单（topic 升序）
//        spokePost("/api/spoke/knowledge/entries",
//                new SpokeKnowledgeUpsertRequest(ws, "osk-aardvark", "s", "c"), a.plainKey())
//                .andExpect(status().isOk());
//        String list = spokeGet("/api/spoke/knowledge/entries?workspaceId=" + ws, a.plainKey())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        assertThat(valuesWithPrefix(list, "topic", "osk-")).containsExactly("osk-aardvark", "osk-topic");
//        // 组织隔离：B 组织同 workspaceId 看不到 A 的条目；B 写入不影响 A
//        assertThat(spokeGet("/api/spoke/knowledge/entries?workspaceId=" + ws, b.plainKey())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("[]");
//        spokeGet("/api/spoke/knowledge/entry?workspaceId=" + ws + "&topic=osk-topic", b.plainKey())
//                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
//        // 不存在的条目 → 404
//        spokeGet("/api/spoke/knowledge/entry?workspaceId=" + ws + "&topic=osk-nope", a.plainKey())
//                .andExpect(status().isNotFound());
//    }
//
//    // ---------- 工作区黑板云同步 ----------
//
//    @Test
//    void blackboard_appendSeqArchiveAndGuards() throws Exception {
//        Env e = newEnv("bb");
//        String ws = "ws-osk-blackboard";
//        String key = "osk-book";
//        // append：seq 服务端分配 1,2,3；type 缺省 note
//        spokePost("/api/spoke/blackboard/entries",
//                new SpokeBlackboardAppendRequest(ws, key, "main", "conclusion", "第一条"), e.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.seq").value(1))
//                .andExpect(jsonPath("$.type").value("conclusion"));
//        spokePost("/api/spoke/blackboard/entries",
//                new SpokeBlackboardAppendRequest(ws, key, "sub-1", null, "第二条"), e.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.seq").value(2))
//                .andExpect(jsonPath("$.type").value("note"));
//        spokePost("/api/spoke/blackboard/entries",
//                new SpokeBlackboardAppendRequest(ws, key, "main", "risk", "第三条"), e.plainKey())
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.seq").value(3));
//        // 归档本（key 含 .archived-）拒绝追加 → 400
//        spokePost("/api/spoke/blackboard/entries",
//                new SpokeBlackboardAppendRequest(ws, key + ".archived-123", "x", null, "y"), e.plainKey())
//                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
//        // 归档前：books 一本未归档
//        String booksBefore = spokeGet("/api/spoke/blackboard/books?workspaceId=" + ws, e.plainKey())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        JsonNode before = findRow(booksBefore, "key", key);
//        assertThat(before).isNotNull();
//        assertThat(before.get("entryCount").asLong()).isEqualTo(3);
//        assertThat(before.get("archived").asBoolean()).isFalse();
//        // 归档整本 → 原本清空，归档本（key 后缀 .archived-<millis>）保留全部行
//        spokePost("/api/spoke/blackboard/archive",
//                new SpokeBlackboardArchiveRequest(ws, key), e.plainKey())
//                .andExpect(status().isOk());
//        String booksAfter = spokeGet("/api/spoke/blackboard/books?workspaceId=" + ws, e.plainKey())
//                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        assertThat(findRow(booksAfter, "key", key)).isNull();
//        JsonNode archived = null;
//        for (JsonNode n : om.readTree(booksAfter)) {
//            if (n.get("key").asText().startsWith(key + ".archived-")) {
//                archived = n;
//            }
//        }
//        assertThat(archived).isNotNull();
//        assertThat(archived.get("archived").asBoolean()).isTrue();
//        assertThat(archived.get("archivedAt").asLong()).isPositive();
//        assertThat(archived.get("entryCount").asLong()).isEqualTo(3);
//        // 归档本条目仍可读（seq 升序）
//        String archivedKey = archived.get("key").asText();
//        String entries = spokeGet("/api/spoke/blackboard/entries?workspaceId=" + ws + "&bookKey=" + archivedKey,
//                e.plainKey()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
//        assertThat(valuesWithPrefix(entries, "content", "第")).containsExactly("第一条", "第二条", "第三条");
//        // 归档不存在的本 → 404
//        spokePost("/api/spoke/blackboard/archive",
//                new SpokeBlackboardArchiveRequest(ws, "osk-nope"), e.plainKey())
//                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
//    }
//}
