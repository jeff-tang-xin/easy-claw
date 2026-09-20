package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.menu.CreateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.menu.UpdateMenuItemRequest;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.workspace.CreateWorkspaceRequest;
import com.xinl.easyclaw.hub.contract.workspace.UpdateWorkspaceRequest;
import com.xinl.easyclaw.hub.support.HubIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作区 + 公共菜单管理面：1:1 绑定、权限跟随 project 可见性矩阵、菜单 key 派生/唯一/级联删除。
 * 用户名统一 ws_ 前缀，slug 统一 ws- 前缀；断言稳定错误码，不断言中文文案。
 */
class WorkspaceMenuIntegrationTest extends HubIntegrationTestSupport {

    private static final String PW = "password123";

    /** owner/admin/member/guest 四角色组织 + 组织外用户。 */
    private record Fixture(String owner, String admin, String member, String guest, String outsider, long orgId) {
    }

    private Fixture newFixture(String tag) throws Exception {
        String owner = createUserAndLogin("ws_" + tag + "_owner", PW);
        String admin = createUserAndLogin("ws_" + tag + "_admin", PW);
        String member = createUserAndLogin("ws_" + tag + "_member", PW);
        String guest = createUserAndLogin("ws_" + tag + "_guest", PW);
        String outsider = createUserAndLogin("ws_" + tag + "_out", PW);
        long orgId = createOrg(owner, "Ws " + tag, "ws-" + tag);
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("ws_" + tag + "_admin", "admin"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("ws_" + tag + "_member", "member"), owner)
                .andExpect(status().isCreated());
        postJson("/api/orgs/" + orgId + "/members", new AddMemberRequest("ws_" + tag + "_guest", "guest"), owner)
                .andExpect(status().isCreated());
        return new Fixture(owner, admin, member, guest, outsider, orgId);
    }

    private long createOrg(String token, String name, String slug) throws Exception {
        String json = postJson("/api/orgs", new CreateOrgRequest(name, slug), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createProject(String token, long orgId, String slug, String visibility) throws Exception {
        String json = postJson("/api/projects",
                new CreateProjectRequest(orgId, slug, "Name " + slug, null, visibility), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createWorkspaceOk(String token, long projectId) throws Exception {
        String json = postJson("/api/workspaces", new CreateWorkspaceRequest(projectId, null), token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private long createMenuOk(String token, long wsId, CreateMenuItemRequest req) throws Exception {
        String json = postJson("/api/workspaces/" + wsId + "/menus", req, token)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private static CreateMenuItemRequest menu(String key, String label) {
        return new CreateMenuItemRequest(key, label, null, null, null, null, null, null, null);
    }

    // ---------- 工作区 ----------

    @Test
    void create_defaultsNameToProject_duplicateBindingConflict() throws Exception {
        Fixture f = newFixture("c1");
        long pid = createProject(f.member(), f.orgId(), "ws-c1-p1", null);
        postJson("/api/workspaces", new CreateWorkspaceRequest(pid, "  "), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(pid))
                .andExpect(jsonPath("$.orgId").value(f.orgId()))
                .andExpect(jsonPath("$.name").value("Name ws-c1-p1"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.menuCount").value(0));
        // 1:1：重复绑定 → 409
        postJson("/api/workspaces", new CreateWorkspaceRequest(pid, null), f.owner())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // 显式命名生效
        long pid2 = createProject(f.owner(), f.orgId(), "ws-c1-p2", null);
        postJson("/api/workspaces", new CreateWorkspaceRequest(pid2, "My WS"), f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("My WS"));
        // guest（非项目可编辑角色）→ 403；组织外 → 403；项目不存在 → 404
        postJson("/api/workspaces", new CreateWorkspaceRequest(pid2, null), f.guest())
                .andExpect(status().isForbidden());
        postJson("/api/workspaces", new CreateWorkspaceRequest(pid2, null), f.outsider())
                .andExpect(status().isForbidden());
        postJson("/api/workspaces", new CreateWorkspaceRequest(999999L, null), f.owner())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void list_followsProjectVisibilityMatrix() throws Exception {
        Fixture f = newFixture("vm");
        long pPriv = createProject(f.member(), f.orgId(), "ws-vm-priv", "private");
        long pTeam = createProject(f.member(), f.orgId(), "ws-vm-team", "team");
        long pOwner = createProject(f.owner(), f.orgId(), "ws-vm-opriv", "private");
        long wPriv = createWorkspaceOk(f.member(), pPriv);
        long wTeam = createWorkspaceOk(f.member(), pTeam);
        long wOwner = createWorkspaceOk(f.owner(), pOwner);

        getJson("/api/workspaces?orgId=" + f.orgId(), f.member())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder((int) wPriv, (int) wTeam)));
        getJson("/api/workspaces?orgId=" + f.orgId(), f.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder((int) wPriv, (int) wTeam, (int) wOwner)));
        getJson("/api/workspaces?orgId=" + f.orgId(), f.guest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) wTeam)));
        getJson("/api/workspaces?orgId=" + f.orgId(), f.outsider())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void update_and_archive_permissionAndStatus() throws Exception {
        Fixture f = newFixture("ua");
        long pid = createProject(f.member(), f.orgId(), "ws-ua-p1", null);
        long wid = createWorkspaceOk(f.member(), pid);

        String member2 = createUserAndLogin("ws_ua_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members", new AddMemberRequest("ws_ua_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        patchJson("/api/workspaces/" + wid, new UpdateWorkspaceRequest("Hacked", null), member2)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        patchJson("/api/workspaces/" + wid, new UpdateWorkspaceRequest("Renamed WS", null), f.member())
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed WS"));
        patchJson("/api/workspaces/" + wid, new UpdateWorkspaceRequest(null, "broken"), f.owner())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        patchJson("/api/workspaces/" + wid, new UpdateWorkspaceRequest(null, "archived"), f.admin())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("archived"));
        getJson("/api/workspaces/" + wid, f.member())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("archived"));
    }

    // ---------- 菜单 ----------

    @Test
    void menu_create_keyDerivationUniquenessAndGates() throws Exception {
        Fixture f = newFixture("mk");
        long wid = createWorkspaceOk(f.owner(), createProject(f.owner(), f.orgId(), "ws-mk-p1", null));
        long otherWid = createWorkspaceOk(f.owner(), createProject(f.owner(), f.orgId(), "ws-mk-p2", null));

        // 显式 key + 全字段
        String json = postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest("console", "Console", "grid", "/console", null, "llm.invoke", "owner,admin",
                        5, true), f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menuKey").value("console"))
                .andExpect(jsonPath("$.requiredPerm").value("llm.invoke"))
                .andExpect(jsonPath("$.visibleRoles").value("owner,admin"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andReturn().getResponse().getContentAsString();
        long consoleId = om.readTree(json).get("id").asLong();
        // 子项
        createMenuOk(f.owner(), wid, new CreateMenuItemRequest("console-users", "Users", null, null, consoleId,
                null, null, null, null));
        // key 留空：英文 label 派生 slug
        postJson("/api/workspaces/" + wid + "/menus", menu(null, "Home Page"), f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("home-page"));
        // 纯中文 label 兜底 menu，冲突自动加后缀
        postJson("/api/workspaces/" + wid + "/menus", menu(null, "工作台"), f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("menu"));
        postJson("/api/workspaces/" + wid + "/menus", menu(null, "报表"), f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.menuKey").value("menu-2"));
        // 同工作区 key 冲突 → 409
        postJson("/api/workspaces/" + wid + "/menus", menu("console", "Dup"), f.owner())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // 非法 key 字符 → 400
        postJson("/api/workspaces/" + wid + "/menus", menu("Bad Key!", "X"), f.owner())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 父项跨工作区 → 400
        long foreign = createMenuOk(f.owner(), otherWid, menu("foreign", "F"));
        postJson("/api/workspaces/" + wid + "/menus",
                new CreateMenuItemRequest(null, "Orphan", null, null, foreign, null, null, null, null), f.owner())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 写权限：非项目创建者的普通 member → 403
        postJson("/api/workspaces/" + wid + "/menus", menu("nope", "Nope"), f.member())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void menu_orderUpdateToggleAndCascadeDelete() throws Exception {
        Fixture f = newFixture("mo");
        long wid = createWorkspaceOk(f.owner(), createProject(f.owner(), f.orgId(), "ws-mo-p1", null));
        long a = createMenuOk(f.owner(), wid, new CreateMenuItemRequest("a", "A", null, null, null, null, null, 10, null));
        long b = createMenuOk(f.owner(), wid, new CreateMenuItemRequest("b", "B", null, null, null, null, null, 20, null));
        createMenuOk(f.owner(), wid, new CreateMenuItemRequest("b1", "B1", null, null, b, null, null, 1, null));
        long c = createMenuOk(f.owner(), wid, new CreateMenuItemRequest("c", "C", null, null, null, null, null, 30, null));

        getJson("/api/workspaces/" + wid + "/menus", f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].menuKey", containsInAnyOrder("a", "b", "b1", "c")));

        // 改排序：c 提到最前
        patchJson("/api/menus/" + c, new UpdateMenuItemRequest(null, null, null, null, null, null, 5, null), f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.sortOrder").value(5));
        // 停用 b1
        postJson("/api/menus/" + b + "/toggle?enabled=false", null, f.owner())
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        // 删除 b → 级联删除其子（含已停用的 b1）
        deleteJson("/api/menus/" + b, f.owner()).andExpect(status().isNoContent());
        getJson("/api/workspaces/" + wid + "/menus", f.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].menuKey", contains("c", "a")));
        // 删除不存在的项 → 404
        deleteJson("/api/menus/999999", f.owner())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void menu_readFollowsWorkspaceVisibility() throws Exception {
        Fixture f = newFixture("mv");
        long pid = createProject(f.member(), f.orgId(), "ws-mv-p1", "private");
        long wid = createWorkspaceOk(f.member(), pid);
        createMenuOk(f.member(), wid, menu("secret-menu", "S"));

        // private 工作区：他人 member 读列表 → 403；admin → 200
        getJson("/api/workspaces/" + wid + "/menus", f.admin()).andExpect(status().isOk());
        String member2 = createUserAndLogin("ws_mv_member2", PW);
        postJson("/api/orgs/" + f.orgId() + "/members", new AddMemberRequest("ws_mv_member2", "member"), f.owner())
                .andExpect(status().isCreated());
        getJson("/api/workspaces/" + wid + "/menus", member2)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
