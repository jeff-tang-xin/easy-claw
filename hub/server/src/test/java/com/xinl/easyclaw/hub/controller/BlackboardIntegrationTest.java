package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.blackboard.CreateBlackboardEntryRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目共享黑板（A4）：追加 / 读写权限矩阵（对齐项目可见性）/ 归档（状态标签，幂等）/ 活跃与归档列表隔离。
 * 用户名 bb_ 前缀、组织 bb- 前缀，避免共享 SQLite 库串数据。
 */
class BlackboardIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    private record Fixture(String owner, String admin, String member, String guest, String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        String owner = createUserAndLogin("bb_" + tag + "_owner", PW);
        String admin = createUserAndLogin("bb_" + tag + "_admin", PW);
        String member = createUserAndLogin("bb_" + tag + "_member", PW);
        String guest = createUserAndLogin("bb_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("bb_" + tag + "_out", PW);
        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Org " + tag, "bb-" + tag), owner)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("bb_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("bb_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("bb_" + tag + "_guest", "guest"), owner)
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

    /** 追加一条并断言 200，返回 entryId。 */
    private long append(String token, long projectId, String content) throws Exception {
        String json = postJson("/api/blackboard",
                new CreateBlackboardEntryRequest(projectId, content), token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    // ---------- 追加 / 用户名回填 / 默认状态 ----------

    @Test
    void append_member_activeAndAuthorUsername() throws Exception {
        Fixture f = newFixture("a1");
        long pid = createProject(f.member(), f.orgId(), "a1-p", "team");
        postJson("/api/blackboard", new CreateBlackboardEntryRequest(pid, "第一条记录"), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.authorUsername").value("bb_a1_member"));
    }

    @Test
    void append_blankContent_validation() throws Exception {
        Fixture f = newFixture("a2");
        long pid = createProject(f.member(), f.orgId(), "a2-p", "team");
        postJson("/api/blackboard", new CreateBlackboardEntryRequest(pid, "   "), f.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void append_guestForbidden_outsiderForbidden() throws Exception {
        Fixture f = newFixture("a3");
        long pid = createProject(f.member(), f.orgId(), "a3-p", "team");
        postJson("/api/blackboard", new CreateBlackboardEntryRequest(pid, "x"), f.guest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/blackboard", new CreateBlackboardEntryRequest(pid, "x"), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 读权限矩阵（对齐项目可见性） ----------

    @Test
    void privateProjectBoard_onlyPrivilegedRead() throws Exception {
        Fixture f = newFixture("r1");
        long pid = createProject(f.owner(), f.orgId(), "r1-p", "private");
        append(f.owner(), pid, "私密记录");

        getJson("/api/blackboard?projectId=" + pid, f.member()).andExpect(status().isForbidden());
        getJson("/api/blackboard?projectId=" + pid, f.guest()).andExpect(status().isForbidden());
        getJson("/api/blackboard?projectId=" + pid, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/blackboard?projectId=" + pid, f.admin()).andExpect(status().isOk());
        // public 项目黑板组织外可读。
        long pubPid = createProject(f.owner(), f.orgId(), "r1-pub", "public");
        append(f.owner(), pubPid, "公开记录");
        getJson("/api/blackboard?projectId=" + pubPid, f.outsider()).andExpect(status().isOk());
    }

    @Test
    void teamProjectBoard_outsiderCannotRead() throws Exception {
        Fixture f = newFixture("r2");
        long pid = createProject(f.member(), f.orgId(), "r2-p", "team");
        append(f.member(), pid, "团队记录");
        getJson("/api/blackboard?projectId=" + pid, f.outsider()).andExpect(status().isForbidden());
        getJson("/api/blackboard?projectId=" + pid, f.guest()).andExpect(status().isOk());
    }

    // ---------- 归档 / 列表隔离 ----------

    @Test
    void archive_movesToArchives_andIdempotent() throws Exception {
        Fixture f = newFixture("ar1");
        long pid = createProject(f.member(), f.orgId(), "ar1-p", "team");
        long e1 = append(f.member(), pid, "第一条");
        long e2 = append(f.member(), pid, "第二条");

        // 归档 e1 → 状态变 archived。
        postJson("/api/blackboard/" + e1 + "/archive", null, f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
        // 幂等：再归档一次仍 200 archived。
        postJson("/api/blackboard/" + e1 + "/archive", null, f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
        // 活跃列表只剩 e2；归档列表含 e1。
        getJson("/api/blackboard?projectId=" + pid, f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(e2));
        getJson("/api/blackboard/archives?projectId=" + pid, f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(e1));
    }

    @Test
    void archive_guestForbidden_outsiderForbidden() throws Exception {
        Fixture f = newFixture("ar2");
        long pid = createProject(f.member(), f.orgId(), "ar2-p", "team");
        long e = append(f.member(), pid, "记录");
        postJson("/api/blackboard/" + e + "/archive", null, f.guest())
                .andExpect(status().isForbidden());
        postJson("/api/blackboard/" + e + "/archive", null, f.outsider())
                .andExpect(status().isForbidden());
    }

    @Test
    void archive_notFound() throws Exception {
        Fixture f = newFixture("ar3");
        postJson("/api/blackboard/999999/archive", null, f.member())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}