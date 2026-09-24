package com.xinl.easyclaw.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.config.HubSpokeClient;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.memory.settings.MemorySettingsService;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.tools.SkillScriptTools;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ManageController 云端端点：
 * - 项目清单（/manage/cloud/projects）仍是 hub 代理：验证透传与 cloud 未配置 502。
 * - cloud-binding 三端点已改为 spoke 本地操作（绑定是 spoke 端事实，hub 不维护反向映射）：
 *   验证读写 WorkspaceManager 的 projectId，不触达 hub。
 * 未用到的 controller 依赖以 Mockito mock 占位。
 */
class ManageCloudProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer hub;

    @BeforeEach
    void startHub() throws IOException {
        hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hub.createContext("/api/spoke/projects", ex -> respond(ex, 200,
                "[{\"id\":1,\"name\":\"P One\",\"slug\":\"p-one\"},{\"id\":2,\"name\":\"P Two\",\"slug\":\"p-two\"}]"));
        hub.start();
    }

    @AfterEach
    void cleanup() {
        if (hub != null) {
            hub.stop(0);
        }
    }

    // ---------- 项目清单代理 ----------

    @Test
    void projectsProxyPassthrough() {
        ResponseEntity<String> resp = controller(cloudProps(), mock(WorkspaceManager.class)).cloudProjects();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"slug\":\"p-one\""));
        assertTrue(resp.getBody().contains("\"slug\":\"p-two\""));
    }

    /** cloud 未配置（无 hubUrl/appKey，无状态码）→ 502 Bad Gateway。 */
    @Test
    void cloudNotConfiguredMapsTo502() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller(new CloudProperties(), mock(WorkspaceManager.class)).cloudProjects());

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    // ---------- cloud-binding：spoke 本地绑定（不触达 hub） ----------

    /** 查询：本地 projectId 非空 → bound=true，projectName 恒 null（前端回退显示 #id）。 */
    @Test
    void bindingReadsLocalProjectId() {
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.getProjectId("ws-1")).thenReturn(5L);

        ManageController.CloudBindingView view = controller(cloudProps(), wm).cloudBinding("ws-1");

        assertTrue(view.bound());
        assertEquals(5L, view.projectId());
        assertNull(view.projectName());
    }

    /** 查询：本地 projectId 为 null → bound=false。 */
    @Test
    void bindingUnboundWhenProjectIdNull() {
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.getProjectId("ws-1")).thenReturn(null);

        ManageController.CloudBindingView view = controller(cloudProps(), wm).cloudBinding("ws-1");

        assertFalse(view.bound());
        assertNull(view.projectId());
    }

    /** 绑定/换绑：仅写本地 projectId，不触达 hub。 */
    @Test
    void bindWritesLocalProjectId() {
        WorkspaceManager wm = mock(WorkspaceManager.class);

        ManageController.CloudBindingView view = controller(cloudProps(), wm)
                .bindCloudWorkspace("ws-1", new ManageController.WorkspaceBindRequest(5L));

        verify(wm).updateProjectId("ws-1", 5L);
        assertTrue(view.bound());
        assertEquals(5L, view.projectId());
    }

    /** 解绑：仅清空本地 projectId。 */
    @Test
    void unbindClearsLocalProjectId() {
        WorkspaceManager wm = mock(WorkspaceManager.class);

        ManageController.CloudBindingView view = controller(cloudProps(), wm).unbindCloudWorkspace("ws-1");

        verify(wm).updateProjectId("ws-1", null);
        assertFalse(view.bound());
        assertNull(view.projectId());
    }

    /** projectId 缺失 → 400，且不写本地。 */
    @Test
    void bindWithoutProjectIdRejected() {
        WorkspaceManager wm = mock(WorkspaceManager.class);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller(cloudProps(), wm)
                        .bindCloudWorkspace("ws-1", new ManageController.WorkspaceBindRequest(null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ---------- 辅助 ----------

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private CloudProperties cloudProps() {
        CloudProperties cloud = new CloudProperties();
        cloud.setHubUrl("http://127.0.0.1:" + hub.getAddress().getPort());
        cloud.setAppKey("eck-test-key");
        return cloud;
    }

    /** 项目清单代理依赖 HubSpokeClient + CloudBootstrapService；绑定端点依赖 WorkspaceManager。 */
    private ManageController controller(CloudProperties props, WorkspaceManager wm) {
        return new ManageController(mock(AgentRegistry.class), mock(ToolManagementService.class),
                mock(ToolRegistryService.class), mock(McpConnectionService.class),
                mock(MemorySettingsService.class), wm,
                new CloudBootstrapService(props), mock(SkillScriptTools.class),
                new HubSpokeClient(props));
    }
}
