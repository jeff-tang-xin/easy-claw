package com.xinl.easyclaw.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinl.easyclaw.hub.contract.docs.AssignDocRequest;
import com.xinl.easyclaw.hub.contract.docs.CreateDocRequest;
import com.xinl.easyclaw.hub.contract.docs.UpdateDocRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目文档（需求/任务）：创建与类型 / 读写权限矩阵（对齐项目可见性）/ 乐观锁 409 + 最新快照 /
 * 版本历史 / 负责人本组织校验 / 删除权限。用户名 dc_ 前缀、组织 dc- 前缀，避免共享库串数据。
 */
class DocIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Fixture(String owner, String admin, String member, String guest, String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        String owner = createUserAndLogin("dc_" + tag + "_owner", PW);
        String admin = createUserAndLogin("dc_" + tag + "_admin", PW);
        String member = createUserAndLogin("dc_" + tag + "_member", PW);
        String guest = createUserAndLogin("dc_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("dc_" + tag + "_out", PW);
        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Org " + tag, "dc-" + tag), owner)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("dc_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("dc_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("dc_" + tag + "_guest", "guest"), owner)
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

    /** 建文档并断言 200，返回 docId。 */
    private long createDoc(String token, long projectId, String title, String docType, Long parentId) throws Exception {
        String json = postJson("/api/docs",
                new CreateDocRequest(projectId, title, docType, "body", parentId), token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    // ---------- 创建 / 类型 ----------

    @Test
    void create_memberDefaultRequirement_version1() throws Exception {
        Fixture f = newFixture("c1");
        long pid = createProject(f.member(), f.orgId(), "c1-p", "team");
        postJson("/api/docs", new CreateDocRequest(pid, "需求一", null, "内容", null), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docType").value("requirement"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.ownerUsername").value("dc_c1_member"));
    }

    @Test
    void create_taskUnderRequirement_ok() throws Exception {
        Fixture f = newFixture("c2");
        long pid = createProject(f.member(), f.orgId(), "c2-p", "team");
        long req = createDoc(f.member(), pid, "需求", "requirement", null);
        postJson("/api/docs", new CreateDocRequest(pid, "任务", "task", null, req), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentDocId").value(req));
    }

    @Test
    void create_taskParentInOtherProject_validation() throws Exception {
        Fixture f = newFixture("c3");
        long p1 = createProject(f.member(), f.orgId(), "c3-a", "team");
        long p2 = createProject(f.member(), f.orgId(), "c3-b", "team");
        long reqInP1 = createDoc(f.member(), p1, "需求", "requirement", null);
        postJson("/api/docs", new CreateDocRequest(p2, "任务", "task", null, reqInP1), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void create_invalidType_validation() throws Exception {
        Fixture f = newFixture("c4");
        long pid = createProject(f.member(), f.orgId(), "c4-p", "team");
        postJson("/api/docs", new CreateDocRequest(pid, "x", "epic", null, null), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void create_guestForbidden_outsiderForbidden() throws Exception {
        Fixture f = newFixture("c5");
        long pid = createProject(f.member(), f.orgId(), "c5-p", "team");
        postJson("/api/docs", new CreateDocRequest(pid, "x", null, null, null), f.guest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/docs", new CreateDocRequest(pid, "x", null, null, null), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 读权限矩阵 ----------

    @Test
    void privateProjectDoc_onlyCreatorAndPrivilegedRead() throws Exception {
        Fixture f = newFixture("r1");
        // owner 建 private 项目并写文档。
        long pid = createProject(f.owner(), f.orgId(), "r1-p", "private");
        long docId = createDoc(f.owner(), pid, "私密需求", null, null);

        getJson("/api/docs/" + docId, f.member())
                .andExpect(status().isForbidden());
        getJson("/api/docs/" + docId, f.guest())
                .andExpect(status().isForbidden());
        getJson("/api/docs/" + docId, f.outsider())
                .andExpect(status().isForbidden());
        getJson("/api/docs/" + docId, f.admin()).andExpect(status().isOk());
        // public 项目文档组织外可读。
        long pubPid = createProject(f.owner(), f.orgId(), "r1-pub", "public");
        long pubDoc = createDoc(f.owner(), pubPid, "公开需求", null, null);
        getJson("/api/docs/" + pubDoc, f.outsider()).andExpect(status().isOk());
    }

    @Test
    void teamProjectDoc_outsiderCannotReadOrList() throws Exception {
        // 回归：requireCanRead 旧逻辑里 "team" 分支不区分成员，组织外用户曾可读/列出 team 文档。
        Fixture f = newFixture("r3");
        long pid = createProject(f.member(), f.orgId(), "r3-p", "team");
        long docId = createDoc(f.member(), pid, "团队需求", null, null);
        getJson("/api/docs/" + docId, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/docs?projectId=" + pid, f.outsider()).andExpect(status().isForbidden());
        // 组织成员可读。
        getJson("/api/docs/" + docId, f.member()).andExpect(status().isOk());
    }

    @Test
    void privateProjectDoc_nonCreatorMemberCannotWrite() throws Exception {
        // 回归：requireCanWrite 旧逻辑只判"成员且非 guest"，普通 member 曾可写他人 private 项目。
        Fixture f = newFixture("w1");
        long pid = createProject(f.owner(), f.orgId(), "w1-p", "private");
        long docId = createDoc(f.owner(), pid, "私密需求", null, null);
        // 普通成员对他人 private 项目：读、建、改全部 403。
        getJson("/api/docs/" + docId, f.member()).andExpect(status().isForbidden());
        postJson("/api/docs", new CreateDocRequest(pid, "越权文档", null, null, null), f.member())
                .andExpect(status().isForbidden());
        postJson("/api/docs/" + docId, new UpdateDocRequest(null, "越权内容", 1L), f.member())
                .andExpect(status().isForbidden());
        // 项目创建者本人可写。
        postJson("/api/docs/" + docId, new UpdateDocRequest(null, "创建者改的", 1L), f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void list_filterByType() throws Exception {
        Fixture f = newFixture("r2");
        long pid = createProject(f.member(), f.orgId(), "r2-p", "team");
        long req = createDoc(f.member(), pid, "需求A", "requirement", null);
        createDoc(f.member(), pid, "任务A", "task", req);
        getJson("/api/docs?projectId=" + pid + "&docType=requirement", f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].docType").value("requirement"));
        getJson("/api/docs?projectId=" + pid, f.guest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].docType", containsInAnyOrder("requirement", "task")));
    }

    // ---------- 乐观锁 ----------

    @Test
    void update_versionAdvanceAndHistoryAppend() throws Exception {
        Fixture f = newFixture("u1");
        long pid = createProject(f.member(), f.orgId(), "u1-p", "team");
        String docJson = postJson("/api/docs",
                new CreateDocRequest(pid, "原标题", null, "v1内容", null), f.member())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long docId = om.readTree(docJson).get("id").asLong();

        // member 基于 v1 提交，成功推进到 v2。
        postJson("/api/docs/" + docId, new UpdateDocRequest("新标题", "v2内容", 1L), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.title").value("新标题"));
        // guest 只读，不能改。
        postJson("/api/docs/" + docId, new UpdateDocRequest(null, "hacked", 2L), f.guest())
                .andExpect(status().isForbidden());
        // 历史：v2 + v1。
        getJson("/api/docs/" + docId + "/history", f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
    }

    @Test
    void update_staleVersion_conflictReturnsLatestSnapshot() throws Exception {
        Fixture f = newFixture("u2");
        long pid = createProject(f.owner(), f.orgId(), "u2-p", "team");
        String docJson2 = postJson("/api/docs",
                new CreateDocRequest(pid, "需求", null, "原始", null), f.owner())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long docId = om.readTree(docJson2).get("id").asLong();

        // admin 先把版本推到 v2。
        postJson("/api/docs/" + docId, new UpdateDocRequest(null, "admin改的", 1L), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // member 仍基于 v1 提交 → 409，details 带回最新 v2 快照（编辑区可据此手动合并）。
        String body = postJson("/api/docs/" + docId, new UpdateDocRequest(null, "member改的", 1L), f.member())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.details.version").value(2))
                .andExpect(jsonPath("$.details.content").value("admin改的"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(body);
        long latestVersion = node.get("details").get("version").asLong();

        // member 用最新版本重试，成功到 v3（内容不丢失）。
        postJson("/api/docs/" + docId,
                new UpdateDocRequest(null, "合并后内容", latestVersion), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.content").value("合并后内容"));
    }

    // ---------- 负责人 / 删除 ----------

    @Test
    void assign_orgMember_ok_nonMemberValidation() throws Exception {
        Fixture f = newFixture("a1");
        long pid = createProject(f.member(), f.orgId(), "a1-p", "team");
        long docId = createDoc(f.member(), pid, "需求", null, null);

        // 组织外用户不能当负责人。
        postJson("/api/docs/" + docId + "/assign",
                new AssignDocRequest(userIdOf("dc_a1_out")), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        // 组织成员（member）可当负责人。
        postJson("/api/docs/" + docId + "/assign",
                new AssignDocRequest(userIdOf("dc_a1_member")), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUsername").value("dc_a1_member"));
        // guest 只读，不能当负责人。
        postJson("/api/docs/" + docId + "/assign",
                new AssignDocRequest(userIdOf("dc_a1_guest")), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void delete_creatorAndAdminCan_otherMemberCannot_historyRetained() throws Exception {
        Fixture f = newFixture("d1");
        long pid = createProject(f.member(), f.orgId(), "d1-p", "team");
        long docId = createDoc(f.member(), pid, "需求", null, null);
        postJson("/api/docs/" + docId, new UpdateDocRequest(null, "第二版", 1L), f.member())
                .andExpect(status().isOk());

        // 非创建者普通成员不能删。
        String member2 = createUserAndLogin("dc_d1_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members", new AddMemberRequest("dc_d1_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        deleteJson("/api/docs/" + docId, member2).andExpect(status().isForbidden());

        // 创建者删除 → 204，详情 404，但历史仍可查（追加留痕）。
        deleteJson("/api/docs/" + docId, f.member()).andExpect(status().isNoContent());
        getJson("/api/docs/" + docId, f.owner()).andExpect(status().isNotFound());
        getJson("/api/docs/" + docId + "/history", f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
