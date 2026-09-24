package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.appkey.CreateAppKeyRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardAppendRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardArchiveRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeUpsertRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * spoke 云同步写入（V23 知识库统一 + V24 黑板统一）：
 * knowledge upsert 落 knowledge_items（projectId 必填，同 (projectId, topic) 覆盖），
 * 项目页「知识条目」端点直接可见（source=workspace）；
 * 黑板 append/archive 落 blackboard_entries（projectId 必填，source=workspace、author=0 占位），
 * 平台黑板端点直接可见，spoke 读端点跨 source（人类记录 Agent 可见）。
 * 用户名 wsy_ 前缀、组织 wsy- 前缀，避免共享 SQLite 库串数据。
 */
class WorkspaceSyncIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    /** 建组织 + appkey（返回明文 key）+ 项目，返回各项句柄。 */
    private record Env(String owner, String member, String outsider, long orgId, String plainKey, long projectId) {
    }

    private Env newEnv(String tag, String visibility) throws Exception {
        String owner = createUserAndLogin("wsy_" + tag + "_owner", PW);
        String member = createUserAndLogin("wsy_" + tag + "_member", PW);
        String outsider = createUserAndLogin("wsy_" + tag + "_out", PW);
        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Org " + tag, "wsy-" + tag), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/members", new com.xinl.easyclaw.hub.contract.org.AddMemberRequest(
                "wsy_" + tag + "_member", "member"), owner).andExpect(status().isCreated());
        String keyJson = postJson("/api/orgs/" + orgId + "/appkeys",
                new CreateAppKeyRequest("wsy-key", null, null), owner)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String plainKey = om.readTree(keyJson).get("plainKey").asText();
        String projJson = postJson("/api/projects",
                new CreateProjectRequest(orgId, tag + "-p", "Name " + tag, null, visibility), owner)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long projectId = om.readTree(projJson).get("id").asLong();
        return new Env(owner, member, outsider, orgId, plainKey, projectId);
    }

    private String spokePost(String url, Object body, String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url)
                        .header("Authorization", "Bearer " + appKey)
                        .contentType("application/json")
                        .content(om.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
    }

    private String spokeGet(String url, String appKey) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)
                        .header("Authorization", "Bearer " + appKey))
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- spoke knowledge upsert → 平台「知识条目」端点可见（V23 统一） ----------

    @Test
    void spokeKnowledgeUpsert_visibleInProjectKnowledge() throws Exception {
        Env e = newEnv("v1", "team");
        spokePost("/api/spoke/knowledge/entries",
                new SpokeKnowledgeUpsertRequest("ws-v1", e.projectId(), "topic-a", "sum-a", "content-a"), e.plainKey())
                .isEmpty();

        // 平台知识条目清单：spoke 同步条目可见，来源标记 workspace + 工作区标识
        getJson("/api/knowledge?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].topic").value("topic-a"))
                .andExpect(jsonPath("$[0].summary").value("sum-a"))
                .andExpect(jsonPath("$[0].source").value("workspace"))
                .andExpect(jsonPath("$[0].sourceWorkspaceId").value("ws-v1"))
                .andExpect(jsonPath("$[0].ownerUserId").value(0));

        // 组织成员同样可读（对齐项目可见性）
        getJson("/api/knowledge?projectId=" + e.projectId(), e.member())
                .andExpect(status().isOk());
    }

    @Test
    void spokeKnowledgeUpsert_sameTopic_overwrites() throws Exception {
        Env e = newEnv("v2", "team");
        spokePost("/api/spoke/knowledge/entries",
                new SpokeKnowledgeUpsertRequest("ws-v2", e.projectId(), "dup-topic", "sum-1", "content-1"),
                e.plainKey()).isEmpty();
        spokePost("/api/spoke/knowledge/entries",
                new SpokeKnowledgeUpsertRequest("ws-v2", e.projectId(), "dup-topic", "sum-2", "content-2"),
                e.plainKey()).isEmpty();

        // 同 (projectId, topic) 只有一条，后写覆盖（version 递增）
        getJson("/api/knowledge?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].summary").value("sum-2"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void spokeKnowledgeUpsert_withoutProjectId_rejected() throws Exception {
        Env e = newEnv("v3", "team");
        // V23：projectId 必填（@NotNull），缺失 → 400
        mvc.perform(MockMvcRequestBuilders.post("/api/spoke/knowledge/entries")
                        .header("Authorization", "Bearer " + e.plainKey())
                        .contentType("application/json")
                        .content(om.writeValueAsString(
                                new SpokeKnowledgeUpsertRequest("ws-v3", null, "t", null, "c"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void spokeKnowledgeUpsert_otherOrgProject_notFound() throws Exception {
        Env e = newEnv("v4", "team");
        Env other = newEnv("v4x", "team");
        // 他组织项目 → 404（不泄露存在性），本组织项目清单不受影响
        spokePost("/api/spoke/knowledge/entries",
                new SpokeKnowledgeUpsertRequest("ws-v4", other.projectId(), "t", null, "c"), e.plainKey())
                .isEmpty();
        getJson("/api/knowledge?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- spoke 黑板写入 → 平台黑板端点可见（V24 统一落 blackboard_entries） ----------

    @Test
    void spokeBlackboardSynced_visibleInPlatformBlackboard() throws Exception {
        Env e = newEnv("v5", "team");
        spokePost("/api/spoke/blackboard/entries",
                new SpokeBlackboardAppendRequest("ws-v5", e.projectId(), "main", "agent", "finding", "记录一"),
                e.plainKey()).isEmpty();
        spokePost("/api/spoke/blackboard/entries",
                new SpokeBlackboardAppendRequest("ws-v5", e.projectId(), "main", "agent", "risk", "记录二"),
                e.plainKey()).isEmpty();

        // 平台黑板清单：spoke 同步条目可见，来源标记 workspace + 工作区标识 + Agent 占位作者
        getJson("/api/blackboard?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].source").value("workspace"))
                .andExpect(jsonPath("$[0].sourceWorkspaceId").value("ws-v5"))
                .andExpect(jsonPath("$[0].entryType").value("risk"))
                .andExpect(jsonPath("$[0].authorUserId").value(0));

        // spoke 读端点跨 source：平台用户写的条目 Agent 也能读到（协作核心）
        postJson("/api/blackboard",
                new com.xinl.easyclaw.hub.contract.blackboard.CreateBlackboardEntryRequest(
                        e.projectId(), "人类记录"), e.owner())
                .andExpect(status().isOk());
        String books = spokeGet("/api/spoke/blackboard/books?projectId=" + e.projectId(), e.plainKey());
        JsonNode mainBook = null;
        for (JsonNode n : om.readTree(books)) {
            if ("main".equals(n.get("key").asText())) {
                mainBook = n;
            }
        }
        assertThat(mainBook).isNotNull();
        assertThat(mainBook.get("entryCount").asLong()).isEqualTo(3);
        String entries = spokeGet("/api/spoke/blackboard/entries?projectId=" + e.projectId() + "&bookKey=main",
                e.plainKey());
        // seq 动态编号（id 升序）；平台条目作者回填用户名，Agent 条目固定 "agent"
        JsonNode arr = om.readTree(entries);
        assertThat(arr).hasSize(3);
        assertThat(arr.get(0).get("author").asText()).isEqualTo("agent");
        assertThat(arr.get(0).get("type").asText()).isEqualTo("finding");
        assertThat(arr.get(2).get("author").asText()).isEqualTo("wsy_v5_owner");
        assertThat(arr.get(2).get("content").asText()).isEqualTo("人类记录");
    }

    @Test
    void spokeBlackboardAppend_withoutProjectId_rejected() throws Exception {
        Env e = newEnv("v6", "team");
        // V24：projectId 必填（@NotNull），缺失 → 400
        mvc.perform(MockMvcRequestBuilders.post("/api/spoke/blackboard/entries")
                        .header("Authorization", "Bearer " + e.plainKey())
                        .contentType("application/json")
                        .content(om.writeValueAsString(
                                new SpokeBlackboardAppendRequest("ws-v6", null, "main", "agent", "note", "x"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void spokeBlackboardArchive_onlyWorkspaceEntries() throws Exception {
        Env e = newEnv("v7", "team");
        spokePost("/api/spoke/blackboard/entries",
                new SpokeBlackboardAppendRequest("ws-v7", e.projectId(), "main", "agent", "note", "agent 条"),
                e.plainKey()).isEmpty();
        postJson("/api/blackboard",
                new com.xinl.easyclaw.hub.contract.blackboard.CreateBlackboardEntryRequest(
                        e.projectId(), "平台条"), e.owner())
                .andExpect(status().isOk());

        // 归档整本：仅 source=workspace 的活跃条目转 archived，平台条目不受影响
        spokePost("/api/spoke/blackboard/archive",
                new SpokeBlackboardArchiveRequest("ws-v7", e.projectId(), "main"), e.plainKey()).isEmpty();

        getJson("/api/blackboard?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].content").value("平台条"))
                .andExpect(jsonPath("$[0].status").value("active"));
        getJson("/api/blackboard/archives?projectId=" + e.projectId(), e.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].content").value("agent 条"))
                .andExpect(jsonPath("$[0].status").value("archived"));
        // 归档后本清单：活跃 main 本（平台条仍在）+ 虚拟归档本（agent 条）并存
        String books = spokeGet("/api/spoke/blackboard/books?projectId=" + e.projectId(), e.plainKey());
        JsonNode arr = om.readTree(books);
        assertThat(arr).hasSize(2);
        JsonNode archivedBook = null;
        for (JsonNode n : arr) {
            if (n.get("key").asText().startsWith("main.archived-")) {
                archivedBook = n;
            }
        }
        assertThat(archivedBook).isNotNull();
        assertThat(archivedBook.get("archived").asBoolean()).isTrue();
        assertThat(archivedBook.get("entryCount").asLong()).isEqualTo(1);
        mvc.perform(MockMvcRequestBuilders.post("/api/spoke/blackboard/entries")
                        .header("Authorization", "Bearer " + e.plainKey())
                        .contentType("application/json")
                        .content(om.writeValueAsString(new SpokeBlackboardAppendRequest(
                                "ws-v7", e.projectId(), "main.archived-123", "agent", "note", "y"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void spokeBlackboardRead_otherOrgProject_notFound() throws Exception {
        Env e = newEnv("v8", "team");
        Env other = newEnv("v8x", "team");
        // 他组织项目 → 404（不泄露存在性）；本组织项目正常可读
        spokeGet("/api/spoke/blackboard/books?projectId=" + other.projectId(), e.plainKey())
                .contains("\"code\":\"NOT_FOUND\"");
        spokeGet("/api/spoke/blackboard/books?projectId=" + e.projectId(), e.plainKey()).contains("[]");
    }
}
