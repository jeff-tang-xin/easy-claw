package com.xinl.easyclaw.ops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeOpsServerView;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.type.BuiltinMcpType;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.tools.OpsTools;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ops 内置能力类型 —— 把「在某台远程服务器上执行命令」做成<b>可实例化、可授权给指定智能体</b>
 * 的能力，让 solo 场景的主智能体与子智能体也能用上运维能力（此前 {@code remote_shell} 只在
 * 专属 ops 工作区的最小 toolkit 里注册，外面拿不到）。
 * <p>
 * <b>与专属 ops 工作区的关系</b>：本类只做「定向 + 授权校验」，执行 / 审计 / 渲染 / 截断
 * 全部委托 {@link OpsTools#runOnConn}，两条通道共用同一份实现。刻意不再写一个执行器——
 * db 侧两份实现各自漂移的教训在前。
 * <p>
 * <b>能力的标准：只暴露能力，不暴露地址。</b>实例配置里的 {@code serverKey} 在服务端解析成
 * 目标连接；工具的参数面只有 {@code command}，返回值里不出现主机、端口与账号。要操作第二台
 * 服务器，请再建一个实例，而不是让模型换个地址。
 * <p>
 * <b>与 db 类型的两处刻意差异（不要「统一」掉）</b>：
 * <ol>
 *   <li><b>解析顺序相反</b>：db 是「实例锁定 &gt; 会话绑定」；ops 是<b>会话绑定优先</b>，
 *       实例配置充当的是<b>授权边界</b>而非目标选择器。人在运维页连着 A 的终端时，AI 按实例
 *       配置跑去 B 执行命令是不可接受的——命令有破坏性，而人看不到 B 的实时输出。
 *       两者冲突时直接拒绝（fail-closed），不猜、不悄悄切换。</li>
 *   <li><b>可以在后端自主建连</b>：hub 随目录下发服务器密码（{@link SpokeOpsServerView#password()}，
 *       仅 spoke 服务端快照持有，下发浏览器一律置 null），所以实例锁定的机器没连上时能自己连。
 *       建不了（hub 没配密码 / 平台未下发该机器）就让人去连，<b>绝不向模型索要密码</b>。</li>
 * </ol>
 * 权限语义由 {@code ToolPermissionPolicy} 的不可摘除清单保证：即使用户在授权页勾了「永久允许」，
 * 每条命令仍然弹确认——ops 能力给到智能体，确认强度不得弱于专属 ops 工作区。
 */
@Component
public class OpsMcpTools implements BuiltinMcpType {

    private static final Logger log = LoggerFactory.getLogger(OpsMcpTools.class);

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();

    /** 本类型标识（落 mcp_services.mcp_type） */
    static final String TYPE_ID = "ops";

    private final SshConnectionService ssh;
    private final CloudBootstrapService cloudBootstrap;
    private final OpsTools opsTools;

    public OpsMcpTools(SshConnectionService ssh, CloudBootstrapService cloudBootstrap, OpsTools opsTools) {
        this.ssh = ssh;
        this.cloudBootstrap = cloudBootstrap;
        this.opsTools = opsTools;
    }

    // ==================== BuiltinMcpType 契约 ====================

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public String displayName() {
        return "运维远程执行（逐条确认）";
    }

    /**
     * 实例配置表单：一台服务器一个实例。只描述字段与候选源，<b>不查平台快照</b>——
     * 实际选项由 Controller 按 {@code optionsSource} 补齐。
     * 配置里不存任何凭证：主机、端口、账号、密码都在建立物理连接时由 hub 下发快照解析。
     */
    @Override
    public List<Map<String, Object>> configSchema() {
        return List.of(
                Map.of("key", "serverKey", "label", "目标服务器 *", "type", "select",
                        "required", true, "optionsSource", "hub-ops-servers",
                        "description", "平台下发且已授权的服务器；一台一个实例"));
    }

    @Override
    public List<AgentTool> toolsFor(McpServiceEntity instance) {
        String serverKey = resolveServerKey(instance);
        if (serverKey == null) {
            // 装配阶段不抛错：绑定展开工具名时实例可能尚未配置完整，
            // 清单为空会让子智能体静默失能，比配置错误更难排查
            log.warn("ops 内置实例未配置 serverKey (service={})，工具照常装配，调用时给人可读错误",
                    instance == null ? "-" : instance.getName());
        }
        return List.of(new RemoteExecTool(serverKey));
    }

    /** 从实例 {@code implementationConfig} 读出锁定的 serverKey；缺失/脏数据一律返回 null。 */
    private static String resolveServerKey(McpServiceEntity instance) {
        if (instance == null || instance.getImplementationConfig() == null
                || instance.getImplementationConfig().isBlank()) {
            return null;
        }
        try {
            JsonNode root = CONFIG_MAPPER.readTree(instance.getImplementationConfig());
            JsonNode node = root.get("serverKey");
            if (node == null || node.isNull()) {
                return null;
            }
            String v = node.asText().trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            log.warn("解析内置 ops 服务配置失败 (service={}): {}", instance.getName(), e.getMessage());
            return null;
        }
    }

    // ==================== 目标连接解析 ====================

    /** 解析结果：{@code connId > 0} 为可用目标；否则 {@code error} 是给人看的说明。 */
    private record Target(long connId, String error) {
        static Target ok(long connId) {
            return new Target(connId, null);
        }

        static Target fail(String error) {
            return new Target(0, error);
        }

        boolean usable() {
            return error == null && connId > 0;
        }
    }

    /**
     * 会话绑定优先，实例配置作授权边界：
     * <ol>
     *   <li>会话已绑定连接，且绑定的正是本实例授权的机器 → 用它（人在看哪台，AI 就操作哪台）；</li>
     *   <li>会话绑定的是<b>另一台</b> → 拒绝执行。不猜、不悄悄切换：运维命令有破坏性，
     *       把人看不见的另一台机器当成目标是最坏结果；</li>
     *   <li>会话无绑定 → 用本工作区内该授权机器的活跃连接；</li>
     *   <li>还没有连接 → 按 hub 下发密码自主建一条（同 serverKey 复用既有连接）。</li>
     * </ol>
     */
    private Target resolveTarget(String workspaceId, String serverKey, ToolCallParam param) {
        String sessionId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getSessionId();
        long bound = ssh.connIdForSession(sessionId, workspaceId);
        if (bound > 0) {
            SshConnectionService.OpsServerInfo info = ssh.opsServerInfo(workspaceId, bound);
            if (info == null) {
                return Target.fail("❌ 会话绑定的连接已不可用，请让用户在运维页面重新连接。");
            }
            if (info.serverKey() != null && info.serverKey().equals(serverKey)) {
                return Target.ok(bound);
            }
            return Target.fail("❌ 当前会话连接的服务器与本能力授权的目标不一致，已拒绝执行。"
                    + "请让用户切到对应的终端页签，或由平台调整本服务实例的授权服务器。");
        }
        long existing = activeConnId(workspaceId, serverKey);
        if (existing > 0) {
            return Target.ok(existing);
        }
        return connectLockedServer(workspaceId, serverKey);
    }

    /** 本工作区内该 serverKey 的活跃连接（运维多 tab 场景下按机器定位，不依赖会话）。 */
    private long activeConnId(String workspaceId, String serverKey) {
        if (serverKey == null) {
            return 0;
        }
        return ssh.activeServerConns().stream()
                .filter(c -> workspaceId.equals(c.workspaceId()) && serverKey.equals(c.serverKey()))
                .mapToLong(SshConnectionService.ActiveServerConn::connId)
                .findFirst()
                .orElse(0L);
    }

    /**
     * 按实例锁定的 serverKey 自主建连：主机、端口、账号、密码全部取自 hub 下发快照，
     * 只在工作区内落一条连接（审计与归属仍按 workspace 隔离）。
     * <p>
     * 密码缺失时<b>不向模型索要</b>，直接让人去运维页面连接——密码属于凭证，
     * 出现在工具面里等于让模型经手凭证。
     */
    private Target connectLockedServer(String workspaceId, String serverKey) {
        if (serverKey == null) {
            return Target.fail("❌ 本服务实例未指定目标服务器，请让用户在 MCP 页面完成配置。");
        }
        SpokeOpsServerView server = cloudBootstrap.findOpsServer(serverKey).orElse(null);
        if (server == null) {
            return Target.fail("❌ 平台配置里已找不到本能力授权的那台服务器（可能已下线或撤销授权）。"
                    + "请让用户确认平台侧的服务器配置。");
        }
        if (server.password() == null || server.password().isBlank()) {
            return Target.fail("❌ 该服务器没有下发免手输密码，无法自动连接。"
                    + "请让用户在运维页面选择它并输入密码连接，然后重试。");
        }
        try {
            long connId = ssh.connectByServer(workspaceId, serverKey, server.name(),
                    server.host(), server.port() > 0 ? server.port() : 22,
                    server.username(), server.password(), server.osType());
            log.info("内置 ops 实例自主建连: workspace={}, connId={}", workspaceId, connId);
            return Target.ok(connId);
        } catch (IOException e) {
            return Target.fail("❌ 连接目标服务器失败：" + e.getMessage()
                    + "。请让用户在运维页面手动连接确认凭据与网络。");
        }
    }

    // ==================== remote_shell（内置 ops 形态） ====================

    /**
     * 目标机器锁在<b>工具实例的 final 字段</b>里，刻意不用本类的实例字段：
     * {@code OpsMcpTools} 是单例 bean，多个 ops 实例共用同一个对象，若把 serverKey 挂在字段上，
     * 后装配的实例会串到前一个实例的目标机器上——运维场景里这等于是把命令下错机器。
     */
    private class RemoteExecTool implements AgentTool {

        private final String serverKey;

        RemoteExecTool(String serverKey) {
            this.serverKey = serverKey;
        }

        @Override public String getName() { return "remote_shell"; }

        @Override public String getDescription() {
            return "在本服务授权的远程服务器上执行一条 shell 命令，返回退出码与 stdout/stderr。\n"
                    + "【前置】每条命令都会向用户弹确认，无法绕过；被拒绝时不要重试，先问用户。\n"
                    + "【注意】命令在真实服务器上执行，输出以服务器实际返回为准，禁止臆造。\n"
                    + "【边界】目标机器由平台装配决定，参数里无法也无需指定；要操作另一台，"
                    + "请让平台再建一个能力实例。";
        }

        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("command", Map.of("type", "string",
                    "description", "要执行的 shell 命令（单条；多条用 && 或 ; 串联）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            // 参数面只有业务命令：目标机器、主机、端口、账号一律不进（只暴露能力，不暴露地址）
            schema.put("required", List.of("command"));
            return schema;
        }

        @Override public boolean isReadOnly() { return false; }

        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String workspaceId = workspaceId(param);
                if (workspaceId == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                String command = str(param.getInput().get("command"));
                if (command == null || command.isBlank()) {
                    return text("❌ command 不能为空。");
                }
                Target target = resolveTarget(workspaceId, serverKey, param);
                if (!target.usable()) {
                    return text(target.error());
                }
                // 执行 / 审计 / 渲染 / 截断走 OpsTools 那一份实现，与专属 ops 工作区完全一致
                return text(opsTools.runOnConn(command, workspace(param), param.getRuntimeContext(),
                        target.connId()));
            }).onErrorResume(ex -> {
                log.error("remote_shell(内置 ops) 异常", ex);
                return Mono.just(error("remote_shell 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== 上下文解析与结果封装 ====================

    /** MCP 工具调用同样经 RuntimeContext 携带 WorkspaceContext（buildContext 注入） */
    private static WorkspaceContext workspace(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            return null;
        }
        return param.getRuntimeContext().get(WorkspaceContext.class);
    }

    private static String workspaceId(ToolCallParam param) {
        WorkspaceContext ws = workspace(param);
        return ws == null ? null : ws.getWorkspaceId();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static ToolResultBlock text(String s) {
        return ToolResultBlock.text(s);
    }

    private static ToolResultBlock error(String s) {
        return ToolResultBlock.error(s);
    }
}
