package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.project.TransferProjectRequest;
import com.xinl.easyclaw.hub.contract.project.UpdateProjectRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目管理：创建角色门槛 / slug 唯一 / 可见性（private|team|public）× 角色（owner|admin|member|guest|组织外）矩阵 / 修改与归档。
 * 用户名统一 pj_ 前缀，组织 slug 统一 pj- 前缀。
 */
class ProjectIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    /** 一套测试现场：owner/admin/member/guest 四角色齐全的组织 + 一名组织外用户。 */
    private record Fixture(String owner, String admin, String member, String guest, String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        String owner = createUserAndLogin("pj_" + tag + "_owner", PW);
        String admin = createUserAndLogin("pj_" + tag + "_admin", PW);
        String member = createUserAndLogin("pj_" + tag + "_member", PW);
        String guest = createUserAndLogin("pj_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("pj_" + tag + "_out", PW);

        String orgJson = postJson("/api/orgs", new CreateOrgRequest("Org " + tag, "pj-" + tag), owner)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orgId = om.readTree(orgJson).get("id").asLong();
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("pj_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("pj_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("pj_" + tag + "_guest", "guest"), owner)
                .andExpect(status().isCreated());
        return new Fixture(owner, admin, member, guest, outsider, orgId);
    }

    /** 建项目并断言 200，返回项目 id。visibility 传 null 走默认 team。 */
    private long createProject(String token, long orgId, String slug, String visibility) throws Exception {
        String json = postJson("/api/projects",
                new CreateProjectRequest(orgId, slug, "Name " + slug, null, visibility), token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    @Test
    void create_memberOk_defaultsTeamAndActive() throws Exception {
        Fixture f = newFixture("c1");
        postJson("/api/projects", new CreateProjectRequest(f.orgId(), "c1-p1", "P1", null, null), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(f.orgId()))
                .andExpect(jsonPath("$.visibility").value("team"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void create_guestForbidden_outsiderForbidden() throws Exception {
        Fixture f = newFixture("c2");
        postJson("/api/projects", new CreateProjectRequest(f.orgId(), "c2-p1", "P1", null, null), f.guest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson("/api/projects", new CreateProjectRequest(f.orgId(), "c2-p2", "P2", null, null), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void create_duplicateSlug_conflict() throws Exception {
        Fixture f = newFixture("c3");
        createProject(f.member(), f.orgId(), "c3-dup", null);
        postJson("/api/projects", new CreateProjectRequest(f.orgId(), "c3-dup", "Dup", null, null), f.owner())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void create_invalidVisibility_validation() throws Exception {
        Fixture f = newFixture("c4");
        postJson("/api/projects", new CreateProjectRequest(f.orgId(), "c4-p1", "P1", null, "secret"), f.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void list_visibilityMatrixByRole() throws Exception {
        Fixture f = newFixture("vm");
        // member 建 private + team；owner 建 private。
        createProject(f.member(), f.orgId(), "vm-mem-priv", "private");
        createProject(f.member(), f.orgId(), "vm-mem-team", "team");
        createProject(f.owner(), f.orgId(), "vm-owner-priv", "private");

        // 创建者（member）：自己的 private + team。
        getJson("/api/projects?orgId=" + f.orgId(), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", containsInAnyOrder("vm-mem-priv", "vm-mem-team")));
        // admin：全量。
        getJson("/api/projects?orgId=" + f.orgId(), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug",
                        containsInAnyOrder("vm-mem-priv", "vm-mem-team", "vm-owner-priv")));
        // guest：仅 team（不含他人 private）。
        getJson("/api/projects?orgId=" + f.orgId(), f.guest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", containsInAnyOrder("vm-mem-team")));
        // 组织外用户：403。
        getJson("/api/projects?orgId=" + f.orgId(), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void get_publicReadableByOutsider_privateMatrix() throws Exception {
        Fixture f = newFixture("g1");
        long pub = createProject(f.owner(), f.orgId(), "g1-pub", "public");
        long priv = createProject(f.owner(), f.orgId(), "g1-priv", "private");
        long team = createProject(f.owner(), f.orgId(), "g1-team", "team");

        // public：组织外登录用户可读。
        getJson("/api/projects/" + pub, f.outsider()).andExpect(status().isOk());
        // private：组织外 403；非创建者的普通成员 403；admin 200；owner 200。
        getJson("/api/projects/" + priv, f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        getJson("/api/projects/" + priv, f.member())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        getJson("/api/projects/" + priv, f.admin()).andExpect(status().isOk());
        getJson("/api/projects/" + priv, f.owner()).andExpect(status().isOk());
        // team：普通成员 200；组织外 403。
        getJson("/api/projects/" + team, f.member()).andExpect(status().isOk());
        getJson("/api/projects/" + team, f.outsider())
                .andExpect(status().isForbidden());
        // 不存在：404。
        getJson("/api/projects/999999", f.owner())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void update_creatorAndAdminCan_otherMemberCannot() throws Exception {
        Fixture f = newFixture("u1");
        long pid = createProject(f.member(), f.orgId(), "u1-p1", "team");

        // 非创建者普通成员 → 403。
        String member2 = createUserAndLogin("pj_u1_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members",
                new AddMemberRequest("pj_u1_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        patchJson("/api/projects/" + pid, new UpdateProjectRequest("Hacked", null, null, null), member2)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 创建者 → 200 且字段生效。
        patchJson("/api/projects/" + pid, new UpdateProjectRequest("Renamed", null, null, null), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
        // admin → 200。
        patchJson("/api/projects/" + pid, new UpdateProjectRequest(null, "by admin", "private", null), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("by admin"))
                .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    void update_invalidStatusAndVisibility_validation() throws Exception {
        Fixture f = newFixture("u2");
        long pid = createProject(f.owner(), f.orgId(), "u2-p1", null);
        patchJson("/api/projects/" + pid, new UpdateProjectRequest(null, null, null, "deleted"), f.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        patchJson("/api/projects/" + pid, new UpdateProjectRequest(null, null, "secret", null), f.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void archive_creatorCan_otherMemberCannot_statusBecomesArchived() throws Exception {
        Fixture f = newFixture("a1");
        long pid = createProject(f.member(), f.orgId(), "a1-p1", "team");

        String member2 = createUserAndLogin("pj_a1_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members",
                new AddMemberRequest("pj_a1_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        deleteJson("/api/projects/" + pid, member2)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        deleteJson("/api/projects/" + pid, f.member())
                .andExpect(status().isNoContent());
        getJson("/api/projects/" + pid, f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    // ============ 迁移（transfer） ============

    /** 建第二个组织（目标组织），返回 orgId。 */
    private long createTargetOrg(String tag, String ownerToken) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest("Target " + tag, "pj-tgt-" + tag), ownerToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    @Test
    void transfer_adminOk_orgAndOwnerChanged() throws Exception {
        Fixture f = newFixture("tr1");
        long pid = createProject(f.member(), f.orgId(), "tr1-p1", "team");
        long targetOrg = createTargetOrg("tr1", f.owner());

        // admin 迁移成功：org_id 改为目标组织，owner 改为 admin。
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(targetOrg));
        // 迁移后 admin 是项目 owner，可读。
        getJson("/api/projects/" + pid, f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(targetOrg));
        // 原组织成员自动脱离：源组织列表不再包含该项目，get 403。
        getJson("/api/projects?orgId=" + f.orgId(), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("tr1-p1"))));
        getJson("/api/projects/" + pid, f.member())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_ownerOk_orgAndOwnerChanged() throws Exception {
        Fixture f = newFixture("tr2");
        long pid = createProject(f.member(), f.orgId(), "tr2-p1", "team");
        long targetOrg = createTargetOrg("tr2", f.owner());

        // owner 迁移成功：org_id 改为目标组织，owner 改为 owner。
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(targetOrg));
    }

    @Test
    void transfer_memberGuestOutsiderForbidden() throws Exception {
        Fixture f = newFixture("tr2b");
        long pid = createProject(f.member(), f.orgId(), "tr2b-p1", "team");
        long targetOrg = createTargetOrg("tr2b", f.owner());

        // member 不可迁移。
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.member())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // guest 不可迁移。
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.guest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 组织外用户不可迁移。
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.outsider())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transfer_platformAdminOk_orgAndOwnerChanged() throws Exception {
        Fixture f = newFixture("tr2c");
        long pid = createProject(f.member(), f.orgId(), "tr2c-p1", "team");
        long targetOrg = createTargetOrg("tr2c", f.owner());

        // 平台管理员（非源组织成员）迁移成功：org_id 改为目标组织，owner 改为平台管理员。
        createPlatformAdminOk("pj_tr2c_padmin", PW);
        String padmin = loginOk("pj_tr2c_padmin", PW).accessToken();
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), padmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(targetOrg));
    }

    @Test
    void transfer_slugConflict_conflict() throws Exception {
        Fixture f = newFixture("tr3");
        long pid = createProject(f.member(), f.orgId(), "tr3-dup", "team");
        long targetOrg = createTargetOrg("tr3", f.owner());
        // 目标组织里已有同名 slug。
        createProject(f.owner(), targetOrg, "tr3-dup", "team");

        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(targetOrg), f.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void transfer_targetOrgNotFound_notFound() throws Exception {
        Fixture f = newFixture("tr4");
        long pid = createProject(f.member(), f.orgId(), "tr4-p1", "team");
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(999999L), f.admin())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void transfer_sameOrg_validation() throws Exception {
        Fixture f = newFixture("tr5");
        long pid = createProject(f.member(), f.orgId(), "tr5-p1", "team");
        postJson("/api/projects/" + pid + "/transfer", new TransferProjectRequest(f.orgId()), f.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }
}
