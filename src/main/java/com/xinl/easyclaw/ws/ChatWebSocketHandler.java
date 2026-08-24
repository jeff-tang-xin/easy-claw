package com.xinl.easyclaw.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天 WebSocket 处理器（全局单连接，sessionId 区分会话）。
 * <p><b>消息分层（前端 → 后端，JSON）：</b></p>
 * <ul>
 *   <li><b>心跳类</b>：{@code {"type":"ping"}} → 后端回 {@code {"type":"pong"}}（保活/探活，不进 LLM）</li>
 *   <li><b>业务类</b>：{@code {"type":"register"|"pending"|"stop", "sessionId":..}}
 *       —— 会话注册/挂起查询/停止（不进 LLM）</li>
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

    private final Set<WebSocketSession> activeSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessionWorkspaceIds = new ConcurrentHashMap<>();
    private final Map<String, PendingBuffer> pendingEvents = new ConcurrentHashMap<>();
    /** 已警告过 workspaceId 为空的 sessionId（避免每条事件刷一个 warn） */
    private final Set<String> warnedEmptyWorkspace = ConcurrentHashMap.newKeySet();

    public ChatWebSocketHandler(AgentService agentService, WorkspaceManager workspaceManager) {
        this.agentService = agentService;
        this.workspaceManager = workspaceManager;
    }

    private static final class PendingBuffer {
        final List<String> events = new ArrayList<>();
        final long createdAt = System.currentTimeMillis();
        long lastAccessAt = System.currentTimeMillis();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 全局单连接：连接建立即加入活跃集合，事件一律广播（前端按 sessionId 路由）
        activeSessions.add(session);
        log.info("WS 连接建立: id={}, 活跃连接数={}", session.getId(), activeSessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        try {
            JsonNode root = mapper.readTree(payload);
            String type = root.path("type").asText("");
            String sessionId = root.path("sessionId").asText("");
            if (sessionId.isBlank()) {
                // ping 等连接级消息不带 sessionId：仍可继续处理
                if (!"ping".equals(type)) {
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
                case "chat" -> handleChat(root, sessionId);
                case "confirm" -> handleConfirm(root, sessionId);
                case "stop" -> handleStop(root);
                case "pending" -> handlePending(root, sessionId);
                case "register" -> handleRegister(root, sessionId);
                default -> log.warn("未知 WS 消息类型: {}", type);
            }
        } catch (Exception e) {
            log.warn("WS 消息解析失败: {}", e.getMessage());
        }
    }

    private void handleRegister(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
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
            broadcastJson(json);
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

    private boolean hasOpenSession() {
        for (WebSocketSession s : activeSessions) {
            if (s.isOpen()) return true;
        }
        return false;
    }

    private void broadcastJson(String json) {
        boolean sent = false;
        for (WebSocketSession s : activeSessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(json));
                    sent = true;
                } catch (Exception e) {
                    log.warn("WS 发送失败: err={}", e.getMessage());
                }
            }
        }
        if (!sent) {
            log.debug("WS broadcast 无活跃连接");
        }
    }

    private void handleChat(JsonNode root, String sessionId) {
        String workspaceId = root.path("workspaceId").asText("");
        if (!workspaceId.isBlank()) {
            sessionWorkspaceIds.put(sessionId, workspaceId);
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
        log.info("WS chat 收到: workspaceId={}, sessionId={}, skill={}, msgLen={}, atts={}",
                workspaceId, sessionId, skillName, msg.length(), atts.size());
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
        if ("turn".equals(action)) {
            agentService.allowTurn(sessionId, toolNames);
        } else if ("always".equals(action)) {
            agentService.allowPermanently(workspaceId, toolNames);
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
        agentService.stopChat(workspaceId, sessionId);
        // 主动推 stopped 事件让前端立即复位（dispose 后 Flux 不再推，不能等它自己发）。
        // 用 stopped 而非 end：end 会触发前端 messageQueue 自动发送下一条，与「停止」语义相反。
        sendJson(sessionId, StreamEvent.stopped());
        log.info("WS stop 完成: workspaceId={}, sessionId={}", workspaceId, sessionId);
    }

    private void sendJson(String sessionId, StreamEvent evt) {
        String json;
        String workspaceId = sessionWorkspaceIds.getOrDefault(sessionId, "");
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("workspaceId", workspaceId);
            envelope.put("sessionId", sessionId);
            envelope.put("event", evt);
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("WS JSON 序列化失败: sessionId={}, err={}", sessionId, e.getMessage());
            return;
        }
        if (workspaceId.isEmpty() && warnedEmptyWorkspace.add(sessionId)) {
            log.warn("WS sendJson workspaceId 为空（仅首次）: sessionId={}, type={}", sessionId, evt == null ? "?" : evt.type());
        }
        if (!hasOpenSession()) {
            bufferEvent(sessionId, json);
            return;
        }
        broadcastJson(json);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session);
        log.info("WS 连接关闭: id={}, 活跃连接数={}, status={}",
                session.getId(), activeSessions.size(), status);
    }
}
