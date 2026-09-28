package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.ops.service.OpsCommandLogReporter;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运维场景工具（仅在激活「运维」场景的工作区装配，见 WorkspaceAgentBuilder）。
 * <p>
 * 当前只有一个工具 {@code remote_shell}：在远程 Linux 服务器上执行命令。
 * 权限语义：不进 SILENTLY_ALLOWED、且在 ALWAYS_ASK 强制集合里 ——
 * 每次调用都弹用户确认，用户点「本轮总是允许/永久允许」也无法让它静音
 * （见 ToolPermissionPolicy 与 WorkspaceAgentBuilder.buildPermissionContext）。
 */
@Component
public class OpsTools {

    private static final Logger log = LoggerFactory.getLogger(OpsTools.class);
    /** 返回给模型的输出上限：超长输出截断，防止吃满上下文 */
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final SshConnectionService ssh;
    private final OpsCommandLogReporter commandLogReporter;

    public OpsTools(SshConnectionService ssh, OpsCommandLogReporter commandLogReporter) {
        this.ssh = ssh;
        this.commandLogReporter = commandLogReporter;
    }

    @Tool(name = "remote_shell", description = "在当前会话绑定的远程 Linux 服务器上执行一条 shell 命令，返回退出码与 stdout/stderr。\n"
            + "【何时用】运维场景：查看服务状态、日志、磁盘/内存/进程、部署与重启服务等。\n"
            + "【前置】用户必须先在运维页面建立连接；未连接时返回错误，此时提示用户先连接，不要重试。\n"
            + "【注意】每次调用都会向用户弹确认（无法绕过）；命令在远程服务器真实执行，输出以服务器实际返回为准，禁止臆造。")
    public String remoteShell(
            @ToolParam(name = "command", description = "要执行的 shell 命令（单条；多条用 && 或 ; 串联）") String command,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        if (workspace == null || workspace.getWorkspaceId() == null) {
            return "❌ 当前没有可用的工作区。";
        }
        if (command == null || command.isBlank()) {
            return "❌ command 不能为空。";
        }
        String workspaceId = workspace.getWorkspaceId();
        // 运维多 tab：一个连接一个会话。会话绑定了连接 → 固定执行在该连接上；
        // 未绑定（如 REST 入口）→ 回退工作区最近建立的连接
        long boundConn = ssh.connIdForSession(rc == null ? null : rc.getSessionId(), workspaceId);
        // 命令审计：执行前入队（无论执行成败都记——记录的是「执行了什么」）。
        // 服务器信息取自目标连接的运行时元数据；info 为 null（本地模式/连接不存在）时不记
        long targetConn = boundConn > 0 ? boundConn : ssh.primaryConnId(workspaceId);
        SshConnectionService.OpsServerInfo serverInfo =
                targetConn > 0 ? ssh.opsServerInfo(workspaceId, targetConn) : null;
        if (serverInfo != null) {
            commandLogReporter.enqueue(serverInfo.serverKey(), serverInfo.name(),
                    serverInfo.host(), command, "ai");
        }
        try {
            SshConnectionService.ExecResult r;
            if (boundConn > 0) {
                if (!ssh.isConnected(workspaceId, boundConn)) {
                    return "❌ 该会话绑定的远程连接已断开：请让用户在运维页面重新连接后再试。";
                }
                r = ssh.exec(workspaceId, boundConn, command, 0);
            } else {
                if (!ssh.isConnected(workspaceId)) {
                    return "❌ 尚未连接远程服务器：请让用户先在运维页面选择连接并点击「连接」，再重试。";
                }
                r = ssh.exec(workspaceId, command);
            }
            StringBuilder sb = new StringBuilder();
            if (serverInfo != null) {
                sb.append(serverBanner(serverInfo)).append('\n');
            }
            sb.append("exit=").append(r.exitCode());
            if (r.timedOut()) {
                sb.append("（超时被终止）");
            }
            sb.append("\n--- stdout ---\n")
                    .append(r.stdout().isEmpty() ? "(空)" : r.stdout())
                    .append("\n--- stderr ---\n")
                    .append(r.stderr().isEmpty() ? "(空)" : r.stderr());
            String result = sb.toString();
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS)
                        + "\n...（输出过长已截断，可用 head/tail/grep 缩小范围后重查）";
            }
            log.info("remote_shell 执行完成: workspace={}, conn={}, exit={}, command={}",
                    workspaceId, boundConn > 0 ? boundConn : "primary", r.exitCode(), command);
            return result;
        } catch (Exception e) {
            log.warn("remote_shell 执行失败: workspace={}, command={}, err={}",
                    workspaceId, command, e.getMessage());
            return "❌ 执行失败: " + e.getMessage();
        }
    }

    /** 结果首行的服务器信息横幅：osType 为空时省略该段 */
    private static String serverBanner(SshConnectionService.OpsServerInfo info) {
        String os = info.osType() == null || info.osType().isBlank() ? null : info.osType().trim();
        return os == null
                ? "[服务器: " + info.name() + " | " + info.username() + "@" + info.host() + "]"
                : "[服务器: " + info.name() + " | " + os + " | " + info.username() + "@" + info.host() + "]";
    }

    // ==================== 终端上下文（AI 的「眼睛」） ====================

    /** snapshot 返回字符上限：与 remote_shell 输出上限一致，防止吃满上下文 */
    private static final int MAX_SNAPSHOT_CHARS = 60_000;

    @Tool(name = "terminal_snapshot", description = "读取当前会话所绑定终端的最近屏幕内容（用户在终端里手动执行命令的真实输出，已去除颜色等控制符，常见凭证已脱敏）。\n"
            + "【何时用】用户问「这是什么情况/刚才为什么报错/你看下现在的输出」，或你需要了解用户在终端手动操作的结果时——先读本工具获取上下文，不要让用户手动复制粘贴。\n"
            + "【边界】只含终端最近的输出（有容量上限，更早的内容可能已被覆盖）；只读、不执行任何命令。\n"
            + "【注意】未连接/未开过终端时返回错误，此时提示用户先连接。")
    public String terminalSnapshot(WorkspaceContext workspace, RuntimeContext rc) {
        return readScroll(workspace, rc, false, 0);
    }

    @Tool(name = "terminal_tail", description = "读取当前会话所绑定终端最近 N 行输出（用户手动操作的真实输出，已去除控制符，常见凭证已脱敏）。\n"
            + "【何时用】只需要看最后几行（如最近一次命令的结果、报错尾部）时用本工具，比 terminal_snapshot 更省上下文。\n"
            + "【边界】只读、不执行命令；N 建议 20~200。")
    public String terminalTail(
            @ToolParam(name = "lines", description = "要读取的最近行数（如 50）") Integer lines,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        int n = lines == null ? 50 : Math.max(1, Math.min(2000, lines));
        return readScroll(workspace, rc, true, n);
    }

    /** snapshot/tail 共用：解析目标连接 → 取缓冲 → 截断 */
    private String readScroll(WorkspaceContext workspace, RuntimeContext rc, boolean tail, int lines) {
        if (workspace == null || workspace.getWorkspaceId() == null) {
            return "❌ 当前没有可用的工作区。";
        }
        String workspaceId = workspace.getWorkspaceId();
        long boundConn = ssh.connIdForSession(rc == null ? null : rc.getSessionId(), workspaceId);
        long targetConn = boundConn > 0 ? boundConn : ssh.primaryConnId(workspaceId);
        if (targetConn <= 0) {
            return "❌ 尚未连接远程服务器：请让用户先在运维页面建立连接。";
        }
        String text = tail
                ? ssh.terminalTail(workspaceId, targetConn, lines)
                : ssh.terminalSnapshot(workspaceId, targetConn);
        if (text == null) {
            return "❌ 该连接还没有终端输出（可能刚连接、尚未执行命令）。";
        }
        if (text.isBlank()) {
            return "（终端当前无可见输出）";
        }
        if (!tail && text.length() > MAX_SNAPSHOT_CHARS) {
            text = text.substring(text.length() - MAX_SNAPSHOT_CHARS)
                    + "\n...（仅保留最近部分，更早内容已超出缓冲）";
        }
        // 终端内容出网前脱敏：掩掉常见凭证形态，降低敏感信息进入 LLM 的风险
        return com.xinl.easyclaw.ops.service.TerminalOutputSanitizer.sanitize(text.stripTrailing());
    }
}
