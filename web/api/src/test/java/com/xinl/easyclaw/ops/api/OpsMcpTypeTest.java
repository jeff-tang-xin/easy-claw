package com.xinl.easyclaw.ops.api;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeOpsServerView;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.tools.OpsTools;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ops 内置能力类型的目标解析与安全语义。
 * <p>
 * 这条线的风险不在「装配出几个工具」，而在<b>命令下到哪台机器</b>：运维命令有破坏性，
 * 解析顺序错一次就是生产事故。所以除了静态断言，本测试用 mock 把四种解析分支各钉一遍，
 * 尤其是「会话连着另一台时必须拒绝执行」这条 fail-closed 分支——它绝不能退化成悄悄切换。
 */
@DisplayName("ops 内置类型：会话绑定优先 + 不暴露地址")
class OpsMcpTypeTest {

    private static final String WS = "ws-1";
    private static final String SESSION = "sess-1";
    private static final String SERVER = "prod-web";

    private SshConnectionService ssh;
    private CloudBootstrapService cloud;
    private OpsTools opsTools;
    private OpsMcpTools type;

    @BeforeEach
    void setUp() {
        ssh = mock(SshConnectionService.class);
        cloud = mock(CloudBootstrapService.class);
        opsTools = mock(OpsTools.class);
        type = new OpsMcpTools(ssh, cloud, opsTools);
    }

    private static McpServiceEntity instance(String config) {
        return McpServiceEntity.builder()
                .id(2L).name("prod-web-shell").transport("BUILTIN").mcpType("ops")
                .scope("GLOBAL").implementationConfig(config).build();
    }

    private AgentTool tool() {
        return toolOf(instance("{\"serverKey\":\"" + SERVER + "\"}"));
    }

    private AgentTool toolOf(McpServiceEntity instance) {
        List<AgentTool> tools = type.toolsFor(instance);
        return tools.stream()
                .filter(t -> "remote_shell".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未装配 remote_shell"));
    }

    private static ToolCallParam param(String command) {
        WorkspaceContext ws = WorkspaceContext.builder().workspaceId(WS).build();
        RuntimeContext rc = RuntimeContext.builder().sessionId(SESSION)
                .put(WorkspaceContext.class, ws)
                .build();
        return ToolCallParam.builder()
                .input(command == null ? Map.of() : Map.of("command", command))
                .runtimeContext(rc)
                .build();
    }

    private static String callText(AgentTool tool, String command) {
        ToolResultBlock block = tool.callAsync(param(command)).block();
        assertThat(block).as("工具调用必须返回结果块").isNotNull();
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining("\n"));
    }

    private static SpokeOpsServerView server(String password) {
        return new SpokeOpsServerView(SERVER, "生产 Web 机", "10.0.0.9", 22, "deploy",
                null, 1L, password, password != null, "Linux", null);
    }

    // ==================== 工具面（静态） ====================

    @Test
    @DisplayName("类型契约：typeId=ops，配置表单只要一个 serverKey")
    void typeContract() {
        assertThat(type.typeId()).isEqualTo("ops");
        assertThat(type.configSchema()).extracting(f -> f.get("key")).containsExactly("serverKey");
        assertThat(type.configSchema().get(0).get("optionsSource")).isEqualTo("hub-ops-servers");
    }

    @Test
    @DisplayName("工具面只有 command，且不含任何定位标识")
    void surfaceCarriesOnlyCommand() {
        List<AgentTool> tools = type.toolsFor(instance("{\"serverKey\":\"" + SERVER + "\"}"));

        assertThat(tools).hasSize(1);
        AgentTool tool = tools.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(props).containsOnlyKeys("command");
        assertThat(tool.getParameters().get("required")).isEqualTo(List.of("command"));

        String surface = (tool.getName() + tool.getDescription() + tool.getParameters())
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(surface)
                .doesNotContain("serverkey")
                .doesNotContain("host")
                .doesNotContain("port")
                .doesNotContain("password")
                .doesNotContain("10.0.0");
    }

    @Test
    @DisplayName("实例没配 serverKey → 工具照常装配（绑定展开不能拿到空清单），调用时给人可读错误")
    void unlockedInstanceStillShipsToolButFailsReadably() {
        AgentTool tool = toolOf(instance(null));

        String out = callText(tool, "uptime");

        assertThat(out).contains("未指定目标服务器");
        verify(opsTools, never()).runOnConn(any(), any(), any(), anyLong());
    }

    // ==================== 目标解析（行为） ====================

    @Test
    @DisplayName("会话绑定的正是授权机器 → 用该连接执行")
    void sessionBoundToLockedServerExecutesOnIt() throws Exception {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(7L);
        when(ssh.opsServerInfo(WS, 7L))
                .thenReturn(new SshConnectionService.OpsServerInfo(SERVER, "生产 Web 机", "10.0.0.9", "deploy", "Linux"));
        when(opsTools.runOnConn(eq("uptime"), any(), any(), eq(7L))).thenReturn("exit=0");

        String out = callText(tool(), "uptime");

        assertThat(out).isEqualTo("exit=0");
        verify(opsTools).runOnConn(eq("uptime"), any(), any(), eq(7L));
    }

    @Test
    @DisplayName("会话连的是另一台 → 拒绝执行，绝不悄悄切到授权那台（fail-closed）")
    void conflictingSessionBindingIsRefusedNotRerouted() throws Exception {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(9L);
        when(ssh.opsServerInfo(WS, 9L))
                .thenReturn(new SshConnectionService.OpsServerInfo("staging-web", "预发机", "10.0.0.8", "deploy", "Linux"));

        String out = callText(tool(), "rm -rf /tmp/x");

        assertThat(out).contains("不一致").contains("已拒绝执行");
        verify(opsTools, never()).runOnConn(any(), any(), any(), anyLong());
        // 也不该为了「对齐目标」去自动建连——那等于绕过人当前所在的终端
        verify(ssh, never()).connectByServer(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("会话无绑定 → 用本工作区内该授权机器的活跃连接")
    void noSessionBindingFallsBackToActiveConnOfLockedServer() throws Exception {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(0L);
        when(ssh.activeServerConns()).thenReturn(List.of(
                new SshConnectionService.ActiveServerConn(WS, 11L, SERVER),
                new SshConnectionService.ActiveServerConn(WS, 12L, "other-box")));
        when(opsTools.runOnConn(eq("df -h"), any(), any(), eq(11L))).thenReturn("exit=0");

        assertThat(callText(tool(), "df -h")).isEqualTo("exit=0");
    }

    @Test
    @DisplayName("本工作区还没有该机器的连接 → 按 hub 下发密码自主建连（密码不进工具面）")
    void autoConnectWhenNoActiveConnAndHubProvidesPassword() throws Exception {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(0L);
        when(ssh.activeServerConns()).thenReturn(List.of());
        when(cloud.findOpsServer(SERVER)).thenReturn(Optional.of(server("s3cret")));
        when(ssh.connectByServer(eq(WS), eq(SERVER), anyString(), anyString(), anyInt(),
                anyString(), eq("s3cret"), any())).thenReturn(21L);
        when(opsTools.runOnConn(eq("uptime"), any(), any(), eq(21L))).thenReturn("exit=0");

        assertThat(callText(tool(), "uptime")).isEqualTo("exit=0");
        verify(ssh).connectByServer(eq(WS), eq(SERVER), anyString(), anyString(), anyInt(),
                anyString(), eq("s3cret"), any());
    }

    @Test
    @DisplayName("hub 没下发密码 → 让人去运维页面连，绝不向模型索要密码")
    void missingPasswordAsksHumanNotModel() {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(0L);
        when(ssh.activeServerConns()).thenReturn(List.of());
        when(cloud.findOpsServer(SERVER)).thenReturn(Optional.of(server(null)));

        String out = callText(tool(), "uptime");

        assertThat(out).contains("请让用户在运维页面");
        assertThat(out).doesNotContain("s3cret");
        verify(opsTools, never()).runOnConn(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("平台已不再下发该服务器（撤销授权/下线）→ 明确报错，不降级到别的机器")
    void revokedServerFailsClosed() {
        when(ssh.connIdForSession(SESSION, WS)).thenReturn(0L);
        when(ssh.activeServerConns()).thenReturn(List.of());
        when(cloud.findOpsServer(SERVER)).thenReturn(Optional.empty());

        String out = callText(tool(), "uptime");

        assertThat(out).contains("找不到");
        verify(opsTools, never()).runOnConn(any(), any(), any(), anyLong());
    }
}
