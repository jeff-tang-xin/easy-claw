package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.org.UpdateMemberRoleRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织与成员管理：建组织 / 成员列表可见性 / 加成员 / 改角色 / 移除成员 的角色权限矩阵。
 * 用户名统一 org_ 前缀，slug 统一 org- 前缀。
 */
class OrgMemberIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    /** owner 建组织并断言 200 + role=owner，返回 orgId。 */
    private long createOrg(String ownerToken, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("owner"))
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    /** 建用户并返回 userId（从登录响应里取）。 */
    private long userIdOf(String username) throws Exception {
        return loginOk(username, PW).user().id();
    }

    @Test
    void createOrg_creatorIsOwner_andListedInMyOrgs() throws Exception {
        String owner = createUserAndLogin("org_owner1", PW);
        long orgId = createOrg(owner, "Org One", "org-one");
        getJson("/api/orgs", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(orgId))
                .andExpect(jsonPath("$[0].role").value("owner"));
    }

    @Test
    void createOrg_duplicateSlug_conflict() throws Exception {
        String owner = createUserAndLogin("org_owner2", PW);
        createOrg(owner, "Org Two", "org-two");
        // slug 全局唯一：即使另一个用户来建也冲突。
        String other = createUserAndLogin("org_other2", PW);
        postJson("/api/orgs", new CreateOrgRequest("Org Two Copy", "org-two"), other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void listMembers_nonMember_forbidden() throws Exception {
        String owner = createUserAndLogin("org_owner3", PW);
        long orgId = createOrg(owner, "Org Three", "org-three");
        String outsider = createUserAndLogin("org_out3", PW);
        getJson("/api/orgs/" + orgId + "/members", outsider)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void addMember_ownerAdds_thenMemberCanListMembers() throws Exception {
        String owner = createUserAndLogin("org_owner4", PW);
        long orgId = createOrg(owner, "Org Four", "org-four");
        String member = createUserAndLogin("org_mem4", PW);

        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem4", "member"), owner)
                .andExpect(status().isCreated());
        getJson("/api/orgs/" + orgId + "/members", member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].username", containsInAnyOrder("org_owner4", "org_mem4")));
    }

    @Test
    void addMember_plainMemberForbidden_adminAllowed() throws Exception {
        String owner = createUserAndLogin("org_owner5", PW);
        long orgId = createOrg(owner, "Org Five", "org-five");
        String member = createUserAndLogin("org_mem5", PW);
        String admin = createUserAndLogin("org_adm5", PW);
        String dave = createUserAndLogin("org_dave5", PW);

        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem5", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_adm5", "admin"), owner)
                .andExpect(status().isCreated());
        // 普通成员加人 → 403。
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_dave5", "member"), member)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // admin 加人 → 201。
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_dave5", "member"), admin)
                .andExpect(status().isCreated());
        getJson("/api/orgs/" + orgId + "/members", dave)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void addMember_duplicate_conflict() throws Exception {
        String owner = createUserAndLogin("org_owner6", PW);
        long orgId = createOrg(owner, "Org Six", "org-six");
        createUserOk("org_mem6", null, PW);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem6", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem6", "admin"), owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void addMember_unknownUser_notFound() throws Exception {
        String owner = createUserAndLogin("org_owner7", PW);
        long orgId = createOrg(owner, "Org Seven", "org-seven");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_ghost", "member"), owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateRole_adminPromotesMember() throws Exception {
        String owner = createUserAndLogin("org_owner8", PW);
        long orgId = createOrg(owner, "Org Eight", "org-eight");
        createUserOk("org_mem8", null, PW);
        String admin = createUserAndLogin("org_adm8", PW);
        long memberId = userIdOf("org_mem8");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem8", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_adm8", "admin"), owner)
                .andExpect(status().isCreated());

        patchJson("/api/orgs/" + orgId + "/members/" + memberId,
                new UpdateMemberRoleRequest("admin"), admin)
                .andExpect(status().isNoContent());
        getJson("/api/orgs/" + orgId + "/members", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='org_mem8')].role", hasItem("admin")));
    }

    @Test
    void updateRole_cannotDemoteOwner() throws Exception {
        String owner = createUserAndLogin("org_owner9", PW);
        long orgId = createOrg(owner, "Org Nine", "org-nine");
        String admin = createUserAndLogin("org_adm9", PW);
        long ownerId = userIdOf("org_owner9");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_adm9", "admin"), owner)
                .andExpect(status().isCreated());

        patchJson("/api/orgs/" + orgId + "/members/" + ownerId,
                new UpdateMemberRoleRequest("member"), admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void updateRole_cannotAppointOwner() throws Exception {
        String owner = createUserAndLogin("org_owner10", PW);
        long orgId = createOrg(owner, "Org Ten", "org-ten");
        createUserOk("org_mem10", null, PW);
        long memberId = userIdOf("org_mem10");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem10", "member"), owner)
                .andExpect(status().isCreated());

        patchJson("/api/orgs/" + orgId + "/members/" + memberId,
                new UpdateMemberRoleRequest("owner"), owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void updateRole_invalidRole_validation() throws Exception {
        String owner = createUserAndLogin("org_owner11", PW);
        long orgId = createOrg(owner, "Org Eleven", "org-eleven");
        createUserOk("org_mem11", null, PW);
        long memberId = userIdOf("org_mem11");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem11", "member"), owner)
                .andExpect(status().isCreated());

        patchJson("/api/orgs/" + orgId + "/members/" + memberId,
                new UpdateMemberRoleRequest("superadmin"), owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void removeMember_ownerRemoves_thenLosesAccess() throws Exception {
        String owner = createUserAndLogin("org_owner12", PW);
        long orgId = createOrg(owner, "Org Twelve", "org-twelve");
        String member = createUserAndLogin("org_mem12", PW);
        long memberId = userIdOf("org_mem12");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem12", "member"), owner)
                .andExpect(status().isCreated());

        deleteJson("/api/orgs/" + orgId + "/members/" + memberId, owner)
                .andExpect(status().isNoContent());
        getJson("/api/orgs/" + orgId + "/members", member)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void removeMember_cannotRemoveOwner() throws Exception {
        String owner = createUserAndLogin("org_owner13", PW);
        long orgId = createOrg(owner, "Org Thirteen", "org-thirteen");
        String admin = createUserAndLogin("org_adm13", PW);
        long ownerId = userIdOf("org_owner13");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_adm13", "admin"), owner)
                .andExpect(status().isCreated());

        deleteJson("/api/orgs/" + orgId + "/members/" + ownerId, admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void removeMember_plainMember_forbidden() throws Exception {
        String owner = createUserAndLogin("org_owner14", PW);
        long orgId = createOrg(owner, "Org Fourteen", "org-fourteen");
        String member = createUserAndLogin("org_mem14", PW);
        createUserOk("org_carol14", null, PW);
        long carolId = userIdOf("org_carol14");
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_mem14", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("org_carol14", "member"), owner)
                .andExpect(status().isCreated());

        deleteJson("/api/orgs/" + orgId + "/members/" + carolId, member)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
