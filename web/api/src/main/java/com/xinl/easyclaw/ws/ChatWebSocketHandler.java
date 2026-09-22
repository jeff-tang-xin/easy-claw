package com.xinl.easyclaw.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.agent.event.EventSerializer;
import com.xinl.easyclaw.agent.event.LegacyEventSerializer;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天 WebSocket 处理器（sessionId 区分会话，事件按会话定向投递）。
 * <p><b>消息分层（前端 → 后端，JSON）：</b></p>
 * <ul>
 *   <li><b>心跳类</b>：{@code {"type":"ping"}} → 后端回 {@code {"type":"pong"}}（保活/探活，不进 LLM）</li>
 *   <li><b>业务类</b>：{@code {"type":"register"|"pending"|"stop", "sessionId":..}}
 *       —— 会话注册/挂起查询/停止（不进 LLM）</li>
 *   <li><b>运维终端类</b>：{@code {"type":"term_open"|"term_data"|"term_resize"|"term_close", ...}}
 *       —— 运维场景 SSH 交互终端（不进 LLM），输出以 term_data(base64) 推回</li>
 *   <li><b>对话类（仅这两类驱动 Agent/LLM）</b>：
 *       {@code {"type":"chat","sessionId":..,"message":..,"attachments":[...]}} 与
 *       {@code {"type":"confirm","sessionId":..,"toolNames":[...],"action":"once|turn|always|deny"}}</li>
 * </ul>
 * <p>后端 → 前端：事件统一包装 {@code {"sessionId":..,"event":{"type":"text|tool|confirm|end|..."}}}</p>
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int MAX_BUFFER_PER_SESSION = 500;
    private static final long BUFFER_TTL_MS = 30 * 60 * 1000L;

    private final AgentService agentService;
    private final WorkspaceManager workspaceManager;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 事件 → 前端 JSON 的唯一出口。取 legacy 实现即当前线上协议；
     * 未来协议协商落地时只替换此处的实现，推送逻辑不变。
     */
    private final EventSerializer eventSerializer = new LegacyEventSerializer(mapper);

    /** WS 发送超时（ms）：超过即关闭慢客户端，避免慢消费者拖住事件推送线程 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    /** 单连接发送缓冲上限（字节）：超限自动关闭连接，防止无界堆积 */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    /** connectionId → 线程安全包装后的连接（WebSocketSession 本身非线程安全） */
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    /**
     * [stream-probe] 临时诊断：按会话记录上一个 reasoning 事件的发送时刻与序号，
     * 仅在相邻 reasoning 发送间隔 >150ms 时打印，用于把“后端→浏览器”这一跳与上游区分。
     * 定位流式卡顿根因后整组探针一并删除。
     */
    private final Map<String, long[]> probeReasoningNanos = new ConcurrentHashMap<>();
    private final Map<String, int[]> probeReasoningSeq = new ConcurrentHashMap<>();
    /**
     * sessionId → 订阅该会话的 connectionId 集合。
     * <p>事件只投递给订阅了该 sessionId 的连接，而非全体广播——否则 A 用户的对话正文、
     * 工具参数会原样送到 B 用户浏览器（前端过滤是客户端行为，不构成隔离边界）。
     * 用 Set 而非单值：同一会话允许多标签页同时观看。
     */
    private final Map<String, Set<String>> sessionOwners = new ConcurrentHashMap<>();
    private final Map<String, String> sessionWorkspaceIds = new ConcurrentHashMap<>();
    private final Map<String, PendingBuffer> pendingEvents = new ConcurrentHashMap<>();
    /** 已警告过 workspaceId 为空的 sessionId（避免每条事件刷一个 warn） */
    /** 「workspaceId 为空」告警去重集合的容量上限：仅用于降噪，达到上限后停止记录，防止长跑无界增长 */
    private static final int MAX_WARNED_SESSIONS = 1_000;
    private final Set<String> warnedEmptyWorkspace = ConcurrentHashMap.newKeySet();

    /**
     * WS 入口与 REST 走同一套归属校验（Finding 6）：WS 帧里的 workspaceId / sessionId
     * 同样是纯客户端输入，若只在 REST 侧校验，攻击者改用 WS 即可绕过。
     */
    private final com.xinl.easyclaw.api.WorkspaceAccessGuard accessGuard;
    private final com.xinl.easyclaw.api.ToolConfirmValidator toolConfirmValidator;
    private final com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties;
    private final com.xinl.easyclaw.config.CloudFeatureGate featureGate;
    /** 运维场景 SSH 连接运行时（终端消息 term_* 使用；无连接时这些消息直接报错返回） */
    private final SshConnectionService ssh;

    public ChatWebSocketHandler(AgentService agentService, WorkspaceManager workspaceManager,
                                com.xinl.easyclaw.api.WorkspaceAccessGuard accessGuard,
                                com.xinl.easyclaw.api.ToolConfirmValidator toolConfirmValidator,
                                com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties,
                                com.xinl.easyclaw.config.CloudFeatureGate featureGate,
                                SshConnectionService ssh) {
        this.agentService = agentService;
        this.workspaceManager = workspaceManager;
        this.accessGuard = accessGuard;
        this.toolConfirmValidator = toolConfirmValidator;
        this.agentScopeProperties = agentScopeProperties;
        this.featureGate = featureGate;
        this.ssh = ssh;
    }

    /**
     * 校验 WS 帧中的归属关系，失败时把原因作为 error+end 事件回推给该会话。
     *
     * @return true 表示校验通过，可继续处理
     */
    private boolean guardOk(String workspaceId, String sessionId) {
        try {
            accessGuard.checkSession(workspaceId, sessionId, false);
            return true;
        } catch (RuntimeException e) {
            log.warn("WS 归属校验失败: workspaceId={}, sessionId={}, reason={}",
                    workspaceId, sessionId, e.getMessage());
            sendJson(sessionId, StreamEvent.error("请求被拒绝: " + e.getMessage()));
            sendJson(sessionId, StreamEvent.end());
            return false;
        }
    }

    /**
     * 校验附件数量与总体积。
     *
     * @return null 表示通过，否则为面向用户的错误文案
     */
    private String validateAttachments(List<UserAttachment> atts) {
        if (atts.isEmpty()) {
            return null;
        }
        int maxCount = agentScopeProperties.getAgent().getMaxAttachments();
        long maxBytes = agentScopeProperties.getAgent().getMaxAttachmentBytes();
        if (maxCount > 0 && atts.size() > maxCount) {
            return "附件数量超限：" + atts.size() + " > " + maxCount;
        }
        if (maxBytes > 0) {
            long total = 0L;
            for (UserAttachment a : atts) {
                total += a.base64Data() == null ? 0L : a.base64Data().length();
                // 边累加边判定：避免遍历完才发现超限（此时数据已在内存中）
                if (total > maxBytes) {
                    return "附件总体积超限（上限 " + (maxBytes / 1024 / 1024) + "MB）";
                }
            }
        }
        return null;
    }

    private static final class PendingBuffer {
        final List<String> events = new ArrayList<>();
        final long createdAt = System.currentTimeMillis();
        long lastAccessAt = System.currentTimeMillis();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 用 ConcurrentWebSocketSessionDecorator 包装：多个会话流可能并发向同一连接写入，
        // 裸 WebSocketSession 并发 sendMessage 会抛 TEXT_PARTIAL_WRITING 并撕裂消息帧。
        connections.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        log.info("WS 连接建立: id={}, 活跃连接数={}", session.getId(), connections.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        try {
            JsonNode root = mapper.readTree(payload);
            String type = root.path("type").asText("");
            String sessionId = root.path("sessionId").asText("");
            if (sessionId.isBlank()) {
                // ping / term_* 等连接级消息不带 sessionId：仍可继续处理
                // （term_open/data/resize/close 是裸终端通道，归属由 terminalOwners 管理，
                //   与聊天会话无关；此前只放行 ping，导致运维终端消息被静默丢弃）
                if (!"ping".equals(type) && !type.startsWith("term_")) {
                    log.warn("WS 消息缺少 sessionId: type={}", type);
                    return;
                }
            }
            switch (type) {
                case "ping" -> {
                    // 心跳：直接回 pong（连接级，不注册映射、不进 LLM）
                    try {
                        session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
                case "chat" -> {
                    bindSession(sessionId, session.getId());
                    handleChat(root, sessionId);
                }
                case "confirm" -> {
                    bindSession(sessionId, session.getId());
                    handleConfirm(root, sessionId);
                }
                case "stop" -> handleStop(root);
                case "intervene" -> {
                    bindSession(sessionId, session.getId());
                    handleIntervene(root, sessionId);
                }
                case "pending" -> {
                    bindSession(sessionId, session.getId());
                    handlePending(root, sessionId);
                }
                case "register" -> handleRegister(root, sessionId, session.getId());
                case "term_open" -> handleTermOpen(root, session.getId());
                case "term_data" -> handleTermData(root);
                case "term_resize" -> handleTermResize(root);
                case "term_close" -> handleTermClose(root);
                default -> log.warn("未知 WS 消息类型: {}", type);
            }
        } catch (Exception e) {
            log.warn("WS 消息解析失败: {}", e.getMessage());
        }
    }

    /** 登记「该连接订阅了该会话」，后续事件据此定向投递 */
    private void bindSession(String sessionId, String connectionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionOwners.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
    }

    // ==================== 运维终端（term_* 消息，不进 LLM） ====================

    /** terminalId → 持有该终端的 connectionId（WS 断连时据此关闭其全部终端） */
    private final Map<String, String> terminalOwners = new ConcurrentHashMap<>();

    /**
     * 打开交互终端：{@code {"type":"term_open","workspaceId":..,"connId":..,"terminalId":..,"cols":..,"rows":..}}。
     * 终端输出以 {@code {"type":"term_data","terminalId":..,"data":"<base64>"}} 推回
     * （原始字节含 ANSI 序列，base64 规避 JSON 转义与非法 UTF-8 问题）。
     */
    private void handleTermOpen(JsonNode root, String connectionId) {
        String workspaceId = root.path("workspaceId").asText("");
        long connId = root.path("connId").asLong(0);
        String terminalId = root.path("terminalId").asText("");
        int cols = root.path("cols").asInt(80);
        int rows = root.path("rows").asInt(24);
        if (workspaceId.isBlank() || connId <= 0 || terminalId.isBlank()) {
            sendTermError(connectionId, terminalId, "term_open 缺少 workspaceId/connId/terminalId");
            return;
        }
        String err = ssh.openShell(workspaceId, connId, terminalId, cols, rows, bytes -> {
            WebSocketSession conn = connections.get(connectionId);
            if (conn == null) {
                return; // 连接已断，输出丢弃；终端随 WS 关闭统一清理
            }
            try {
                Map<String, Object> evt = new HashMap<>();
                evt.put("type", "term_data");
                evt.put("terminalId", terminalId);
                evt.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
                conn.sendMessage(new TextMessage(mapper.writeValueAsString(evt)));
            } catch (Exception e) {
                log.debug("终端输出推送失败（连接可能已断）: terminalId={}, {}", terminalId, e.getMessage());
            }
        });
        if (err != null) {
            sendTermError(connectionId, terminalId, err);
            return;
        }
        terminalOwners.put(terminalId, connectionId);
    }

    /** 键盘输入：{@code {"type":"term_data","workspaceId":..,"terminalId":..,"data":"<原文>"}} */
    private void handleTermData(JsonNode root) {
        String workspaceId = root.path("workspaceId").asText("");
        String terminalId = root.path("terminalId").asText("");
        String data = root.path("data").asText("");
        if (terminalId.isBlank() || data.isEmpty()) {
            return;
        }
        String err = ssh.writeShell(workspaceId, terminalId, data);
        if (err != null) {
            WebSocketSession conn = connections.get(terminalOwners.get(terminalId));
            if (conn != null) {
                sendTermError(conn.getId(), terminalId, err);
            }
        }
    }

    /** 窗口尺寸变化：{@code {"type":"term_resize","terminalId":..,"cols":..,"rows":..}} */
    private void handleTermResize(JsonNode root) {
        String workspaceId = root.path("workspaceId").asText("");
        String terminalId = root.path("terminalId").asText("");
        if (terminalId.isBlank()) {
            return;
        }
        ssh.resizeShell(workspaceId, terminalId, root.path("cols").asInt(80), root.path("rows").asInt(24));
    }

    /** 主动关闭终端：{@code {"type":"term_close","terminalId":..}} */
    private void handleTermClose(JsonNode root) {
        String workspaceId = root.path("workspaceId").asText("");
        String terminalId = root.path("terminalId").asText("");
        if (terminalId.isBlank()) {
            return;
        }
        ssh.closeShell(workspaceId, terminalId);
        terminalOwners.remove(terminalId);
    }

    private void sendTermError(String connectionId, String terminalId, String message) {
        WebSocketSession conn = connections.get(connectionId);
        if (conn == null) {
            return;
        }
        try {
            Map<String, Object> evt = new HashMap<>();
            evt.put("type", "term_error");
            evt.put("terminalId", terminalId);
            evt.put("message", message);
            conn.sendMessage(new TextMessage(mapper.writeValueAsString(evt)));
        } catch (Exception e) {
            log.debug("终端错误推送失败: terminalId={}, {}", terminalId, e.getMessage());
        }
    }

    private void handleRegister(JsonNode root, String sessionId, String connectionId) {
        bindSession(sessionId, connectionId);
        String workspaceId = root.path("workspaceId").asText("");
        long connId = root.path("connId").asLong(0);
        if (connId > 0 && !workspaceId.isBlank()) {
            // WS 重连后 register 恢复会话→连接绑定（断连时已解绑；多连接下缺绑定会回退 primary 连接）
            ssh.bindSession(sessionId, workspaceId, connId);
        }
        if (!workspaceId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
            log.info("WS 注册 sessionId→workspaceId: sessionId={}, workspaceId={}", sessionId, workspaceId);
        } else {
            log.warn("WS register 缺少 workspaceId: sessionId={}", sessionId);
        }
        // 主动回推当前会话状态（重连/刷新后前端据此恢复 running / pending UI）
        try {
            Map<String, Object> status = agentService.getSessionStatus(sessionId);
            String json = mapper.writeValueAsString(status);
            sendJson(sessionId, StreamEvent.status(json));
            log.info("WS register 回推状态: sessionId={}, running={}, pending={}",
                    sessionId, status.get("running"), status.get("pending"));
        } catch (Exception e) {
            log.warn("WS register 状态查询失败: sessionId={}, err={}", sessionId, e.getMessage());
        }
        flushPendingEvents(sessionId);
    }

    private void flushPendingEvents(String sessionId) {
        PendingBuffer buf = pendingEvents.remove(sessionId);
        if (buf == null || buf.events.isEmpty()) {
            return;
        }
        List<String> events = new ArrayList<>(buf.events);
        buf.events.clear();
        log.info("WS flush 缓冲事件: sessionId={}, count={}", sessionId, events.size());
        for (String json : events) {
            sendToSession(sessionId, json);
        }
    }

    private void bufferEvent(String sessionId, String json) {
        long now = System.currentTimeMillis();
        pendingEvents.entrySet().removeIf(e -> now - e.getValue().createdAt > BUFFER_TTL_MS);
        PendingBuffer buf = pendingEvents.computeIfAbsent(sessionId, k -> new PendingBuffer());
        buf.lastAccessAt = now;
        buf.events.add(json);
        while (buf.events.size() > MAX_BUFFER_PER_SESSION) {
            buf.events.remove(0);
        }
    }

    /** 该会话是否还有活跃订阅连接（无则事件进缓冲，等重连后 flush） */
    private boolean hasOpenSession(String sessionId) {
        Set<String> owners = sessionOwners.get(sessionId);
        if (owners == null || owners.isEmpty()) {
            return false;
        }
        for (String cid : owners) {
            WebSocketSession s = connections.get(cid);
            if (s != null && s.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 定向投递：只发给订阅了该 sessionId 的连接。
     * <p>发送失败（客户端断开 / 缓冲超限被关闭）的连接立即解绑，避免死引用堆积。
     */
    private void sendToSession(String sessionId, String json) {
        Set<String> owners = sessionOwners.get(sessionId);
        if (owners == null || owners.isEmpty()) {
            log.debug("WS 无订阅连接，事件丢弃: sessionId={}", sessionId);
            return;
        }
        boolean sent = false;
        for (String cid : owners) {
            WebSocketSession s = connections.get(cid);
            if (s == null || !s.isOpen()) {
                owners.remove(cid);
                continue;
            }
            try {
                s.sendMessage(new TextMessage(json));
                sent = true;
            } catch (Exception e) {
                log.warn("WS 发送失败，解绑连接: sessionId={}, cid={}, err={}", sessionId, cid, e.getMessage());
                owners.remove(cid);
                connections.remove(cid);
            }
        }
        if (!sent) {
            log.debug("WS 定向投递无可用连接: sessionId={}", sessionId);
        }
    }

    private void handleChat(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
        long connId = root.path("connId").asLong(0);
        if (!workspaceId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
        }
        if (connId > 0 && !workspaceId.isBlank()) {
            // 运维多 tab：一个连接一个会话，该会话的 remote_shell 固定作用于这条连接
            ssh.bindSession(sessionId, workspaceId, connId);
        }
        String msg = root.path("message").asText("");
        String skillName = root.path("skillName").asText("");
        List<UserAttachment> atts = new ArrayList<>();
        JsonNode arr = root.path("attachments");
        if (arr.isArray()) {
            for (JsonNode a : arr) {
                UserAttachment att = new UserAttachment(
                        a.path("name").asText("file"),
                        a.path("mimeType").asText("application/octet-stream"),
                        a.path("base64Data").asText(""));
                // 过滤空附件（前端异常/图片上传失败会产生 base64Data 为空的条目）
                if (att.base64Data() != null && !att.base64Data().isBlank()) {
                    atts.add(att);
                }
            }
        }
        // 空消息防护：文本为空且无有效附件时，不把空消息送给模型
        // （否则模型会收到空 user 消息，回复"你发了一条空消息"之类，污染对话体验）
        if (msg.isBlank() && atts.isEmpty()) {
            log.warn("WS chat 拒绝空消息: workspaceId={}, sessionId={}", workspaceId, sessionId);
            sendJson(sessionId, StreamEvent.error("消息内容为空：请输入文字或添加附件后再发送。"));
            sendJson(sessionId, StreamEvent.end());
            return;
        }
        // 附件门禁（spec §4.3）：组织经 hub 目录关闭 allow_attachments 后，拒绝带附件消息
        if (!atts.isEmpty() && !featureGate.attachmentsAllowed()) {
            log.warn("WS chat 拒绝附件: 组织已禁用附件与图片上传, sessionId={}", sessionId);
            sendJson(sessionId, StreamEvent.error("该组织已禁用附件与图片上传"));
            sendJson(sessionId, StreamEvent.end());
            return;
        }
        log.info("WS chat 收到: workspaceId={}, sessionId={}, skill={}, msgLen={}, atts={}",
                workspaceId, sessionId, skillName, msg.length(), atts.size());
        if (!guardOk(workspaceId, sessionId)) {
            return;
        }
        // 入口即拒超量附件：base64 会整体进内存并拼入消息，放过去等于把堆交给调用方支配
        String attErr = validateAttachments(atts);
        if (attErr != null) {
            log.warn("WS chat 附件校验失败: sessionId={}, reason={}", sessionId, attErr);
            sendJson(sessionId, StreamEvent.error(attErr));
            sendJson(sessionId, StreamEvent.end());
            return;
        }
        final int[] counter = {0};
        try {
            agentService.streamChat(workspaceId, sessionId, msg, atts,
                    skillName.isBlank() ? null : skillName,
                    evt -> {
                        counter[0]++;
                        if (counter[0] <= 5 || counter[0] % 50 == 0 || "end".equals(evt.type())) {
                            log.info("WS event #{}: sessionId={}, type={}", counter[0], sessionId, evt.type());
                        }
                        sendJson(sessionId, evt);
                    },
                    err -> {
                        log.warn("WS 流式错误: sessionId={}, err={}", sessionId,
                                err == null ? "null" : err.getMessage());
                        sendJson(sessionId, StreamEvent.error(err.getMessage() == null ? "未知错误" : err.getMessage()));
                        sendJson(sessionId, StreamEvent.end());
                    },
                    () -> {
                        log.info("WS 流结束回调: sessionId={}, totalEvents={}", sessionId, counter[0]);
                        sendJson(sessionId, StreamEvent.end());
                    });
        } catch (Exception e) {
            log.error("WS 启动流失败: {}", e.getMessage(), e);
            sendJson(sessionId, StreamEvent.error("启动失败: " + e.getMessage()));
            sendJson(sessionId, StreamEvent.end());
        }
    }

    private void handleConfirm(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
        if (!workspaceId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
        }
        String action = root.path("action").asText("once");
        List<String> toolNames = new ArrayList<>();
        JsonNode arr = root.path("toolNames");
        if (arr.isArray()) {
            arr.forEach(n -> toolNames.add(n.asText()));
        }
        if (toolNames.isEmpty()) {
            return;
        }
        if (!guardOk(workspaceId, sessionId)) {
            return;
        }
        // 与 REST /confirm 同一道白名单：未知工具名不得写入永久授权规则
        List<String> unknown = toolConfirmValidator.rejectUnknown(sessionId, toolNames);
        if (!unknown.isEmpty()) {
            log.warn("WS confirm 含未知工具: sessionId={}, unknown={}", sessionId, unknown);
            sendJson(sessionId, StreamEvent.error("未知工具: " + unknown));
            sendJson(sessionId, StreamEvent.end());
            return;
        }
        if ("turn".equals(action)) {
            agentService.allowTurn(sessionId, toolNames);
        } else if ("always".equals(action)) {
            if (!agentService.allowPermanently(workspaceId, toolNames)) {
                // 场景未启用白名单机制（ops）：本次调用照常放行，但明确告知规则未持久化
                sendJson(sessionId, StreamEvent.error("该场景未启用工具白名单：每次调用都会请求确认"));
            }
        }
        boolean allowed = !"deny".equals(action);
        agentService.resumeChat(workspaceId, sessionId, allowed,
                evt -> sendJson(sessionId, evt),
                err -> {
                    log.warn("WS 恢复流错误: sessionId={}, err={}", sessionId,
                            err == null ? "null" : err.getMessage());
                    sendJson(sessionId, StreamEvent.error(err.getMessage() == null ? "未知错误" : err.getMessage()));
                    sendJson(sessionId, StreamEvent.end());
                },
                () -> sendJson(sessionId, StreamEvent.end()));
    }

    private void handlePending(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
        if (!workspaceId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
        }
        try {
            List<Map<String, Object>> tools = agentService.pendingConfirmInfo(sessionId);
            String json = "{\"pending\":" + (!tools.isEmpty())
                    + ",\"tools\":" + mapper.writeValueAsString(tools) + "}";
            sendJson(sessionId, StreamEvent.pendingInfo(json));
        } catch (Exception e) {
            log.warn("WS pending 查询失败: {}", e.getMessage());
        }
    }

    private void handleStop(JsonNode root) {
        String workspaceId = root.path("workspaceId").asText("");
        String sessionId = root.path("sessionId").asText("");
        if (!workspaceId.isBlank() && !sessionId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
        }
        if (!guardOk(workspaceId, sessionId)) {
            return;
        }
        agentService.stopChat(workspaceId, sessionId);
        // 主动推 stopped 事件让前端立即复位（dispose 后 Flux 不再推，不能等它自己发）。
        // 用 stopped 而非 end：end 会触发前端 messageQueue 自动发送下一条，与「停止」语义相反。
        sendJson(sessionId, StreamEvent.stopped());
        log.info("WS stop 完成: workspaceId={}, sessionId={}", workspaceId, sessionId);
    }

    /**
     * 用户主动介入当前轮次：不中断执行，把插话作为 hint 注入正在进行的回合。
     * <p>
     * 与 {@code stop} 的区别：stop 会中断 Agent 并复位前端；intervene 让本轮继续跑，
     * 仅在下一个推理步之前把用户的新指示塞进上下文。
     */
    private void handleIntervene(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
        String text = root.path("content").asText("");
        if (text.isBlank()) {
            // 兼容前端可能使用的 message/text 字段名
            text = root.path("message").asText("");
        }
        if (text.isBlank()) {
            text = root.path("text").asText("");
        }
        if (!workspaceId.isBlank() && !sessionId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
        }
        if (!guardOk(workspaceId, sessionId)) {
            return;
        }
        boolean ok = agentService.interveneTurn(workspaceId, sessionId, text);
        log.info("WS intervene 结果: workspaceId={}, sessionId={}, ok={}", workspaceId, sessionId, ok);
    }

    private void sendJson(String sessionId, StreamEvent evt) {
        String json;
        String workspaceId = sessionWorkspaceIds.getOrDefault(sessionId, "");
        try {
            json = eventSerializer.serialize(workspaceId, sessionId, evt);
        } catch (Exception e) {
            log.warn("WS JSON 序列化失败: sessionId={}, err={}", sessionId, e.getMessage());
            return;
        }
        // [stream-probe] 临时：WS 发送 reasoning 的间隔，定位后删除
        if (evt != null && "reasoning".equals(evt.type())) {
            long[] prev = probeReasoningNanos.computeIfAbsent(sessionId, k -> new long[1]);
            int[] seqBox = probeReasoningSeq.computeIfAbsent(sessionId, k -> new int[1]);
            seqBox[0]++;
            long nowNanos = System.nanoTime();
            if (prev[0] != 0L) {
                long gapMs = (nowNanos - prev[0]) / 1_000_000L;
                if (gapMs > 150L) {
                    log.info("[stream-probe] ws-send reasoning gap={}ms before evt#{} session={}",
                            gapMs, seqBox[0], sessionId);
                }
            }
            prev[0] = nowNanos;
        }
        if (workspaceId.isEmpty() && warnedEmptyWorkspace.size() < MAX_WARNED_SESSIONS
                && warnedEmptyWorkspace.add(sessionId)) {
            log.warn("WS sendJson workspaceId 为空（仅首次）: sessionId={}, type={}", sessionId, evt == null ? "?" : evt.type());
        }
        if (!hasOpenSession(sessionId)) {
            bufferEvent(sessionId, json);
            return;
        }
        sendToSession(sessionId, json);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String cid = session.getId();
        connections.remove(cid);
        // 该连接持有的运维终端全部关闭（远端 PTY 一并释放）
        terminalOwners.entrySet().removeIf(e -> {
            if (cid.equals(e.getValue())) {
                ssh.closeShellByTerminalId(e.getKey());
                return true;
            }
            return false;
        });
        // 解除该连接的所有会话订阅；会话若已无任何订阅者则移除空条目并登记待释放
        List<String> orphaned = new ArrayList<>();
        sessionOwners.entrySet().removeIf(e -> {
            e.getValue().remove(cid);
            if (e.getValue().isEmpty()) {
                orphaned.add(e.getKey());
                return true;
            }
            return false;
        });
        log.info("WS 连接关闭: id={}, 活跃连接数={}, status={}",
                cid, connections.size(), status);
        // 释放已无任何订阅者且确实空转的会话内存状态，避免 Map 只增不减。
        // 用 IfIdle 版本：浏览器刷新/切网也会触发断连，但后端回合可能仍在跑
        // （前端重连后靠 pendingEvents 缓冲续看），不能无条件 dispose 订阅
        for (String sid : orphaned) {
            ssh.unbindSession(sid);
            agentService.releaseSessionIfIdle(sid);
        }
    }
}
