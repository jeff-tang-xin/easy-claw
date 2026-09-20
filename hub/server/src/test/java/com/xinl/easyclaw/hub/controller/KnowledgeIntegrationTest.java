package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.knowledge.CreateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.contract.knowledge.UpdateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目知识库条目（A3-S1）：创建 / 读写权限矩阵（对齐项目可见性）/ topic 项目内唯一与软删重建 /
 * 乐观锁 409 VERSION_CONFLICT 带最新快照 / 版本历史（含软删后可追溯）/ 软删删除权限。
 * 用户名 kn_ 前缀、组织 kn- 前缀，避免共享 SQLite 库串数据。向量/语义检索不在本批（S3 真 PG 验）。
 */
class KnowledgeIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Fixture(String owner, String admin, String member, String guest, String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        String owner = createUserAndLogin("kn_" + tag + "_owner", PW);
        String admin = createUserAndLogin("kn_" + tag + "_admin", PW);
        String member = createUserAndLogin("kn_" + tag + "_member", PW);
        String guest = createUserAndLogin("kn_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("kn_" + tag + "_out", PW);
        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Org " + tag, "kn-" + tag), owner)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("kn_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("kn_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("kn_" + tag + "_guest", "guest"), owner)
                .andExpect(status().isCreated());
        return new Fixture(owner, admin, member, guest, outsider, orgId);
    }

    private long createProject(String token, long orgId, String slug, String visibility) throws Exception {
        String json = postJson("/api/projects",
                new CreateProjectRequest(orgId, slug, "Name " + slug, null, visibility), token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    /** 建条目并断言 200，返回 itemId。 */
    private long createItem(String token, long projectId, String topic) throws Exception {
        return createItem(token, projectId, topic, "摘要", "正文");
    }

    private long createItem(String token, long projectId, String topic, String summary, String content)
            throws Exception {
        String json = postJson("/api/knowledge",
                new CreateKnowledgeItemRequest(projectId, topic, summary, content), token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    // ---------- 创建 / 用户名回填 / 默认状态 ----------

    @Test
    void create_member_version1AndUsernames() throws Exception {
        Fixture f = newFixture("c1");
        long pid = createProject(f.member(), f.orgId(), "c1-p", "team");
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "部署手册", "如何部署", "内容"), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.embeddingStatus").value("pending"))
                .andExpect(jsonPath("$.ownerUsername").value("kn_c1_member"))
                .andExpect(jsonPath("$.updatedByUsername").value("kn_c1_member"));
    }

    @Test
    void create_blankTopic_validation() throws Exception {
        Fixture f = newFixture("c2");
        long pid = createProject(f.member(), f.orgId(), "c2-p", "team");
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "   ", null, null), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void create_guestForbidden_outsiderForbidden() throws Exception {
        Fixture f = newFixture("c3");
        long pid = createProject(f.member(), f.orgId(), "c3-p", "team");
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "x", null, null), f.guest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "x", null, null), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void create_duplicateTopic_topicExists() throws Exception {
        Fixture f = newFixture("c4");
        long pid = createProject(f.member(), f.orgId(), "c4-p", "team");
        createItem(f.member(), pid, "同名");
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "同名", null, null), f.member())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_EXISTS"));
    }

    @Test
    void create_sameTopicInDifferentProjects_ok() throws Exception {
        Fixture f = newFixture("c5");
        long p1 = createProject(f.member(), f.orgId(), "c5-a", "team");
        long p2 = createProject(f.member(), f.orgId(), "c5-b", "team");
        createItem(f.member(), p1, "同名");
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(p2, "同名", null, null), f.member())
                .andExpect(status().isOk());
    }

    // ---------- 读权限矩阵（对齐项目可见性） ----------

    @Test
    void privateProjectItem_onlyCreatorAndPrivilegedRead() throws Exception {
        Fixture f = newFixture("r1");
        long pid = createProject(f.owner(), f.orgId(), "r1-p", "private");
        long itemId = createItem(f.owner(), pid, "私密知识");

        getJson("/api/knowledge/" + itemId, f.member()).andExpect(status().isForbidden());
        getJson("/api/knowledge/" + itemId, f.guest()).andExpect(status().isForbidden());
        getJson("/api/knowledge/" + itemId, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/knowledge/" + itemId, f.admin()).andExpect(status().isOk());
        // public 项目知识组织外可读。
        long pubPid = createProject(f.owner(), f.orgId(), "r1-pub", "public");
        long pubItem = createItem(f.owner(), pubPid, "公开知识");
        getJson("/api/knowledge/" + pubItem, f.outsider()).andExpect(status().isOk());
    }

    @Test
    void teamProjectItem_outsiderCannotReadOrList() throws Exception {
        Fixture f = newFixture("r2");
        long pid = createProject(f.member(), f.orgId(), "r2-p", "team");
        long itemId = createItem(f.member(), pid, "团队知识");
        getJson("/api/knowledge/" + itemId, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/knowledge?projectId=" + pid, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/knowledge/" + itemId, f.guest()).andExpect(status().isOk());
    }

    @Test
    void privateProjectItem_nonCreatorMemberCannotWrite() throws Exception {
        Fixture f = newFixture("w1");
        long pid = createProject(f.owner(), f.orgId(), "w1-p", "private");
        long itemId = createItem(f.owner(), pid, "私密知识");
        getJson("/api/knowledge/" + itemId, f.member()).andExpect(status().isForbidden());
        postJson("/api/knowledge/" + itemId, new UpdateKnowledgeItemRequest("越权", null, null, 1L), f.member())
                .andExpect(status().isForbidden());
    }

    // ---------- 乐观锁 / 历史 ----------

    @Test
    void update_versionAdvanceAndHistoryAppend_andUpdatedByChanges() throws Exception {
        Fixture f = newFixture("u1");
        long pid = createProject(f.owner(), f.orgId(), "u1-p", "team");
        long itemId = createItem(f.owner(), pid, "原标题");

        // member 基于 v1 编辑 → v2，updatedBy 变为 member，owner 仍是 owner。
        postJson("/api/knowledge/" + itemId,
                new UpdateKnowledgeItemRequest("新标题", "新摘要", "v2正文", 1L), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.topic").value("新标题"))
                .andExpect(jsonPath("$.ownerUsername").value("kn_u1_owner"))
                .andExpect(jsonPath("$.updatedByUsername").value("kn_u1_member"));
        // guest 只读。
        postJson("/api/knowledge/" + itemId, new UpdateKnowledgeItemRequest("hacked", null, null, 2L), f.guest())
                .andExpect(status().isForbidden());
        // 历史：v2 + v1，倒序。
        getJson("/api/knowledge/" + itemId + "/history", f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[0].actorUsername").value("kn_u1_member"));
        // 读任意历史版本全文。
        getJson("/api/knowledge/" + itemId + "/history/1", f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.topic").value("原标题"))
                .andExpect(jsonPath("$.content").value("正文"));
    }

    @Test
    void update_staleVersion_conflictReturnsLatestSnapshot() throws Exception {
        Fixture f = newFixture("u2");
        long pid = createProject(f.owner(), f.orgId(), "u2-p", "team");
        long itemId = createItem(f.owner(), pid, "主题");

        // admin 先推进到 v2。
        postJson("/api/knowledge/" + itemId,
                new UpdateKnowledgeItemRequest("主题", null, "admin改的", 1L), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // member 仍基于 v1 提交 → 409 VERSION_CONFLICT，details 带最新 v2 快照。
        String body = postJson("/api/knowledge/" + itemId,
                new UpdateKnowledgeItemRequest("主题", null, "member改的", 1L), f.member())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.version").value(2))
                .andExpect(jsonPath("$.details.content").value("admin改的"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(body);
        long latestVersion = node.get("details").get("version").asLong();

        // member 以最新版本重试 → v3，内容不丢失。
        postJson("/api/knowledge/" + itemId,
                new UpdateKnowledgeItemRequest("主题", null, "合并后内容", latestVersion), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.content").value("合并后内容"));
    }

    @Test
    void update_renameToExistingTopic_topicExists_butKeepingOwnNameOk() throws Exception {
        Fixture f = newFixture("u3");
        long pid = createProject(f.member(), f.orgId(), "u3-p", "team");
        long a = createItem(f.member(), pid, "条目A");
        long b = createItem(f.member(), pid, "条目B");
        // b 改名成已存在的「条目A」→ TOPIC_EXISTS。
        postJson("/api/knowledge/" + b, new UpdateKnowledgeItemRequest("条目A", null, null, 1L), f.member())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_EXISTS"));
        // a 保持自己的名字提交（改名到自身）不应误判重名，成功到 v2。
        postJson("/api/knowledge/" + a, new UpdateKnowledgeItemRequest("条目A", "补摘要", null, 1L), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 软删 / 历史保留 / 同名重建 / 列表 ----------

    @Test
    void delete_permissions_historyRetained_andSameNameReusable() throws Exception {
        Fixture f = newFixture("d1");
        long pid = createProject(f.member(), f.orgId(), "d1-p", "team");
        long itemId = createItem(f.member(), pid, "待删知识", null, "v1");
        postJson("/api/knowledge/" + itemId, new UpdateKnowledgeItemRequest("待删知识", null, "v2", 1L), f.member())
                .andExpect(status().isOk());

        // 非创建者普通成员不能删；owner/admin 或创建者可删。
        String member2 = createUserAndLogin("kn_d1_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members", new AddMemberRequest("kn_d1_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        deleteJson("/api/knowledge/" + itemId, member2).andExpect(status().isForbidden());

        deleteJson("/api/knowledge/" + itemId, f.member()).andExpect(status().isNoContent());
        // 列表不再含、详情 404（防枚举已删条目）。
        getJson("/api/knowledge?projectId=" + pid, f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        getJson("/api/knowledge/" + itemId, f.owner()).andExpect(status().isNotFound());
        // 历史仍可按 id 追溯（审计）。
        getJson("/api/knowledge/" + itemId + "/history", f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        // 软删后允许重建同名条目。
        postJson("/api/knowledge", new CreateKnowledgeItemRequest(pid, "待删知识", null, "新正文"), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void list_onlyActive_containsAll() throws Exception {
        // 只断言「未删除条目都在列表」。updatedAt DESC 的先后顺序依赖时间戳精度：
        // 生产 PG 为 TIMESTAMPTZ 微秒（可靠），SQLite 测试库为秒级，同秒顺序不稳定，故不在 SQLite 断言次序。
        Fixture f = newFixture("l1");
        long pid = createProject(f.member(), f.orgId(), "l1-p", "team");
        long a = createItem(f.member(), pid, "条目A");
        long b = createItem(f.member(), pid, "条目B");
        String json = getJson("/api/knowledge?projectId=" + pid, f.member())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (JsonNode n : om.readTree(json)) {
            ids.add(n.get("id").asLong());
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(a, b), ids);
    }
}
