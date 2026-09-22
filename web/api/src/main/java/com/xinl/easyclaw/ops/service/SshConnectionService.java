package com.xinl.easyclaw.ops.service;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 运维场景 SSH 连接运行时管理。
 * <p>
 * 三个职责，全部按 workspaceId 隔离：
 * <ul>
 *   <li><b>连接</b>：每工作区可同时多条活跃连接（按 connId 隔离，各自独立终端 tab）；
 *       最近建立的连接作为智能体 remote_shell 的默认执行目标（primary）。
 *       凭证明文只在调用瞬间存在，不缓存</li>
 *   <li><b>命令执行</b>：{@code exec} 供 remote_shell 工具用（一次性 exec channel，
 *       收集 stdout/stderr/退出码，作用于 primary 连接）；{@code openShell} 供 Web 终端用
 *       （PTY 交互通道，输出经回调推给 WS，作用于指定 connId 的连接）</li>
 *   <li><b>文件上传</b>：SFTP put 到指定连接的远程目录</li>
 * </ul>
 * <p>
 * <b>主机密钥校验</b>：当前显式使用 AcceptAll（首次连接不弹指纹确认）。
 * 运维工具连接的是用户自己填写的内网/自有服务器；严格 TOFU 指纹校验留作后续增强，
 * 届时需给前端加指纹确认交互，不能只改这里。
 */
@Service
public class SshConnectionService {

    private static final Logger log = LoggerFactory.getLogger(SshConnectionService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);
    /** exec 默认超时（秒），与 application.yml 的 shell-timeout-seconds 对齐 */
    private static final long DEFAULT_EXEC_TIMEOUT_SECONDS = 300;

    /** (workspaceId|connId) → 活跃连接；同一工作区可并存多条（不同 connId） */
    private final Map<String, SshHolder> active = new ConcurrentHashMap<>();
    /** workspaceId → 最近建立连接的 connId（智能体 remote_shell 的默认目标） */
    private final Map<String, Long> primaryConn = new ConcurrentHashMap<>();
    /** sessionId → 绑定的连接键 wsKey（运维多 tab：一个连接一个会话，remote_shell 定向执行） */
    private final Map<String, String> sessionConn = new ConcurrentHashMap<>();
    /** terminalId → 交互终端（跨工作区索引，WS 断连时按 terminalId 清理） */
    private final Map<String, ShellEntry> terminals = new ConcurrentHashMap<>();

    /** 连接运行时索引键：同一工作区按 connId 并存多条连接 */
    private static String wsKey(String workspaceId, long connId) {
        return workspaceId + "|" + connId;
    }

    // ==================== 连接管理 ====================

    /**
     * 建立指定连接配置的活跃连接（同 connId 重连时先关旧；其他 connId 的连接不受影响）。
     * 凭证明文参数在方法返回后即丢弃。
     *
     * @throws IOException 连接/认证失败（消息已可直接展示给用户）
     */
    public void connect(String workspaceId, long connId, String connName, String host, int port, String username,
                        String authType, String password, String privateKey, String passphrase)
            throws IOException {
        disconnect(workspaceId, connId);
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        boolean ok = false;
        try {
            ClientSession session = client.connect(username, host, port)
                    .verify(CONNECT_TIMEOUT).getSession();
            if ("key".equals(authType)) {
                if (privateKey == null || privateKey.isBlank()) {
                    throw new IOException("私钥内容为空");
                }
                Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                        null, NamedResource.ofName("ops-key"),
                        new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
                        FilePasswordProvider.of(passphrase == null ? "" : passphrase));
                boolean added = false;
                for (KeyPair kp : pairs) {
                    session.addPublicKeyIdentity(kp);
                    added = true;
                    break;
                }
                if (!added) {
                    throw new IOException("私钥解析失败：无法从中读取密钥对（支持 OpenSSH/PEM 格式）");
                }
            } else {
                if (password == null) {
                    throw new IOException("密码为空");
                }
                session.addPasswordIdentity(password);
            }
            session.auth().verify(AUTH_TIMEOUT);
            active.put(wsKey(workspaceId, connId), new SshHolder(client, session, connId, connName, host, username, Instant.now()));
            primaryConn.put(workspaceId, connId);
            ok = true;
            log.info("运维连接已建立: workspace={}, conn={}, {}@{}:{}", workspaceId, connId, username, host, port);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("连接失败: " + e.getMessage(), e);
        } finally {
            if (!ok) {
                try {
                    client.stop();
                } catch (Exception ignored) {
                    // 关闭失败无需处理
                }
            }
        }
    }

    /** 断开指定连接并关闭其全部交互终端；该连接未活跃时静默返回 */
    public void disconnect(String workspaceId, long connId) {
        SshHolder holder = active.remove(wsKey(workspaceId, connId));
        if (holder == null) {
            return;
        }
        if (primaryConn.get(workspaceId) != null && primaryConn.get(workspaceId) == connId) {
            primaryConn.remove(workspaceId);
        }
        terminals.entrySet().removeIf(e -> {
            if (workspaceId.equals(e.getValue().workspaceId) && e.getValue().connId == connId) {
                closeQuietly(e.getValue());
                return true;
            }
            return false;
        });
        try {
            holder.session.close(false);
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
        try {
            holder.client.stop();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
        log.info("运维连接已断开: workspace={}, conn={}, {}@{}", workspaceId, connId, holder.username, holder.host);
    }

    /** 工作区 primary 连接（最近建立）是否可用 —— remote_shell 工具的前置判断 */
    public boolean isConnected(String workspaceId) {
        return primaryHolder(workspaceId) != null;
    }

    /** 指定连接是否活跃 */
    public boolean isConnected(String workspaceId, long connId) {
        SshHolder holder = active.get(wsKey(workspaceId, connId));
        return holder != null && holder.session.isOpen() && holder.session.isAuthenticated();
    }

    /** 连接状态快照（给前端展示；不含任何凭证）。connections 为该工作区全部活跃连接 */
    public Map<String, Object> status(String workspaceId) {
        Map<String, Object> out = new HashMap<>();
        List<Map<String, Object>> conns = new ArrayList<>();
        for (Map.Entry<String, SshHolder> e : active.entrySet()) {
            if (!e.getKey().startsWith(workspaceId + "|")) {
                continue;
            }
            SshHolder h = e.getValue();
            if (!h.session.isOpen() || !h.session.isAuthenticated()) {
                continue;
            }
            Map<String, Object> c = new HashMap<>();
            c.put("connId", h.connId);
            c.put("connName", h.connName);
            c.put("host", h.host);
            c.put("username", h.username);
            c.put("connectedAt", h.connectedAt.toString());
            conns.add(c);
        }
        out.put("connections", conns);
        return out;
    }

    // ==================== 会话绑定（智能幕布按 tab 会话定向执行） ====================

    /**
     * 绑定「会话 → 连接」：该会话里智能体的 remote_shell 固定作用于这条连接。
     * 运维页每个连接一个智能体会话（前端 chat 消息携带 connId 时调用）。
     */
    public void bindSession(String sessionId, String workspaceId, long connId) {
        if (sessionId == null || sessionId.isBlank() || workspaceId == null
                || workspaceId.isBlank() || connId <= 0) {
            return;
        }
        sessionConn.put(sessionId, wsKey(workspaceId, connId));
    }

    /** 解除会话绑定（WS 断连清理 orphaned 会话时调用，防 map 无界增长） */
    public void unbindSession(String sessionId) {
        if (sessionId != null) {
            sessionConn.remove(sessionId);
        }
    }

    /**
     * 会话绑定的连接 id；未绑定或绑定不属于该工作区时返回 0（调用方回退 primary 连接）。
     * 校验 workspace 前缀，防止拿 A 工作区会话的绑定去操作 B 工作区的连接。
     */
    public long connIdForSession(String sessionId, String workspaceId) {
        String key = sessionId == null ? null : sessionConn.get(sessionId);
        if (key == null || workspaceId == null || workspaceId.isBlank()
                || !key.startsWith(workspaceId + "|")) {
            return 0;
        }
        try {
            return Long.parseLong(key.substring(workspaceId.length() + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 命令执行（remote_shell 工具） ====================

    /**
     * 在远程执行一条命令并收集完整输出（阻塞）。
     *
     * @param timeoutSeconds 超时秒数；超时后通道被强制关闭并返回 timedOut 结果
     */
    public ExecResult exec(String workspaceId, String command, long timeoutSeconds) throws IOException {
        SshHolder holder = primaryHolder(workspaceId);
        long timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_EXEC_TIMEOUT_SECONDS;
        return execOn(holder, workspaceId, command, timeout);
    }

    public ExecResult exec(String workspaceId, String command) throws IOException {
        return exec(workspaceId, command, DEFAULT_EXEC_TIMEOUT_SECONDS);
    }

    /** 在指定连接上执行命令（会话绑定了连接时由 remote_shell 使用） */
    public ExecResult exec(String workspaceId, long connId, String command, long timeoutSeconds) throws IOException {
        SshHolder holder = active.get(wsKey(workspaceId, connId));
        if (holder == null || !holder.session.isOpen() || !holder.session.isAuthenticated()) {
            throw new IOException("该会话绑定的远程连接已断开（connId=" + connId + "）：请重新连接后再试");
        }
        long timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_EXEC_TIMEOUT_SECONDS;
        return execOn(holder, workspaceId, command, timeout);
    }

    private ExecResult execOn(SshHolder holder, String workspaceId, String command, long timeout) throws IOException {
        if (holder == null) {
            throw new IOException("尚未连接远程服务器");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ClientChannel channel = holder.session.createExecChannel(command)) {
            channel.setOut(out);
            channel.setErr(err);
            channel.open().verify(CONNECT_TIMEOUT);
            Set<ClientChannelEvent> events =
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(timeout));
            boolean timedOut = events.contains(ClientChannelEvent.TIMEOUT);
            if (timedOut) {
                log.warn("远程命令超时: workspace={}, command={}, timeout={}s", workspaceId, command, timeout);
            }
            Integer code = channel.getExitStatus();
            return new ExecResult(code == null ? -1 : code,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8),
                    timedOut);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("远程执行失败: " + e.getMessage(), e);
        }
    }

    // ==================== 交互终端（Web shell） ====================

    /**
     * 打开一个 PTY 交互终端。输出（含 ANSI 控制序列）经 {@code outputConsumer}
     * 以原始字节块回调（在 sshd 读线程上调用，消费方自行保证线程安全）。
     *
     * @return 错误消息；null = 成功
     */
    public String openShell(String workspaceId, long connId, String terminalId, int cols, int rows,
                            Consumer<byte[]> outputConsumer) {
        SshHolder holder = active.get(wsKey(workspaceId, connId));
        if (holder == null || !holder.session.isOpen()) {
            return "尚未连接远程服务器";
        }
        ShellEntry existing = terminals.get(terminalId);
        if (existing != null) {
            closeQuietly(existing);
            terminals.remove(terminalId);
        }
        try {
            ChannelShell channel = (ChannelShell) holder.session.createShellChannel();
            channel.setPtyType("xterm-256color");
            channel.setPtyColumns(cols > 0 ? cols : 80);
            channel.setPtyLines(rows > 0 ? rows : 24);
            PipedInputStream in = new PipedInputStream(64 * 1024);
            PipedOutputStream stdin = new PipedOutputStream(in);
            channel.setIn(in);
            channel.setOut(new ForwardingOutputStream(outputConsumer));
            channel.setErr(new ForwardingOutputStream(outputConsumer));
            channel.open().verify(CONNECT_TIMEOUT);
            terminals.put(terminalId, new ShellEntry(workspaceId, connId, channel, stdin));
            log.info("交互终端已打开: workspace={}, conn={}, terminal={}, {}@{}",
                    workspaceId, connId, terminalId, holder.username, holder.host);
            return null;
        } catch (Exception e) {
            return "打开终端失败: " + e.getMessage();
        }
    }

    /** 向终端写入输入（键盘输入/粘贴）；返回错误消息，null = 成功 */
    public String writeShell(String workspaceId, String terminalId, String data) {
        ShellEntry entry = terminals.get(terminalId);
        if (entry == null || !workspaceId.equals(entry.workspaceId)) {
            return "终端不存在或已关闭";
        }
        try {
            entry.stdin.write(data.getBytes(StandardCharsets.UTF_8));
            entry.stdin.flush();
            return null;
        } catch (IOException e) {
            closeShell(workspaceId, terminalId);
            return "终端输入失败（已关闭）: " + e.getMessage();
        }
    }

    /**
     * 终端窗口尺寸变化。
     * <p>
     * <b>已知限制</b>：MINA SSHD 2.12.1 未暴露 window-change 通道请求的公开 API
     * （PtyCapableChannelSession/ClientChannel 均无 resize 方法），故此处仅记录日志、
     * 不向远端发送尺寸变更；远端 PTY 保持打开时的初始行列数，bash 的行回绕在
     * resize 后可能出现短暂错位，不影响功能。后续升级 SSHD 或自行实现
     * window-change 报文后再补齐。
     */
    public String resizeShell(String workspaceId, String terminalId, int cols, int rows) {
        ShellEntry entry = terminals.get(terminalId);
        if (entry == null || !workspaceId.equals(entry.workspaceId)) {
            return null; // 终端已关，resize 静默忽略
        }
        log.debug("PTY resize 暂不支持（SSHD 2.12.1 无公开 API）: terminal={}, {}x{}", terminalId, cols, rows);
        return null;
    }

    public void closeShell(String workspaceId, String terminalId) {
        ShellEntry entry = terminals.remove(terminalId);
        if (entry != null && workspaceId.equals(entry.workspaceId)) {
            closeQuietly(entry);
        }
    }

    /** WS 连接断开时按 terminalId 清理（不校验 workspace，调用方来自连接关闭回调） */
    public void closeShellByTerminalId(String terminalId) {
        ShellEntry entry = terminals.remove(terminalId);
        if (entry != null) {
            closeQuietly(entry);
        }
    }

    // ==================== 文件上传（SFTP） ====================

    /**
     * 上传文件到远程目录（SFTP put）。
     *
     * @param targetDir 远程目录（须已存在），如 {@code /home/user/upload}
     * @return 错误消息；null = 成功
     */
    public String upload(String workspaceId, long connId, String targetDir, String fileName, InputStream content) {
        SshHolder holder = active.get(wsKey(workspaceId, connId));
        if (holder == null || !holder.session.isOpen()) {
            return "尚未连接远程服务器";
        }
        String safeName = fileName == null ? "upload.bin" : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String remotePath = (targetDir == null || targetDir.isBlank() ? "." : targetDir.trim())
                + "/" + safeName;
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(holder.session)) {
            try {
                sftp.stat(targetDir == null || targetDir.isBlank() ? "." : targetDir.trim());
            } catch (Exception e) {
                return "远程目录不存在: " + targetDir;
            }
            try (OutputStream os = sftp.write(remotePath)) {
                content.transferTo(os);
            }
            log.info("文件已上传: workspace={}, -> {}", workspaceId, remotePath);
            return null;
        } catch (Exception e) {
            return "上传失败: " + e.getMessage();
        }
    }

    // ==================== 内部结构 ====================

    /** 工作区 primary 连接（最近建立的）；无活跃连接返回 null，exec 侧据此报错 */
    private SshHolder primaryHolder(String workspaceId) {
        Long connId = primaryConn.get(workspaceId);
        if (connId == null) {
            return null;
        }
        SshHolder holder = active.get(wsKey(workspaceId, connId));
        if (holder == null || !holder.session.isOpen() || !holder.session.isAuthenticated()) {
            return null;
        }
        return holder;
    }

    private void closeQuietly(ShellEntry entry) {
        try {
            entry.stdin.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
        try {
            entry.channel.close(false);
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }

    private static final class SshHolder {
        final SshClient client;
        final ClientSession session;
        final long connId;
        final String connName;
        final String host;
        final String username;
        final Instant connectedAt;

        SshHolder(SshClient client, ClientSession session, long connId, String connName,
                  String host, String username, Instant connectedAt) {
            this.client = client;
            this.session = session;
            this.connId = connId;
            this.connName = connName;
            this.host = host;
            this.username = username;
            this.connectedAt = connectedAt;
        }
    }

    private static final class ShellEntry {
        final String workspaceId;
        final long connId;
        final ChannelShell channel;
        final PipedOutputStream stdin;

        ShellEntry(String workspaceId, long connId, ChannelShell channel, PipedOutputStream stdin) {
            this.workspaceId = workspaceId;
            this.connId = connId;
            this.channel = channel;
            this.stdin = stdin;
        }
    }

    /** 把 sshd 推来的字节块原样转发给消费方（不做字符解码，避免多字节字符被包边界劈开） */
    private static final class ForwardingOutputStream extends OutputStream {
        private final Consumer<byte[]> consumer;

        ForwardingOutputStream(Consumer<byte[]> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void write(int b) {
            consumer.accept(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b, int off, int len) {
            byte[] chunk = new byte[len];
            System.arraycopy(b, off, chunk, 0, len);
            consumer.accept(chunk);
        }
    }

    /** 一次性命令执行结果 */
    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }
}
