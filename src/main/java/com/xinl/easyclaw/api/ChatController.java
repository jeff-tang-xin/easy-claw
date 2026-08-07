package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.AgentStateBoxReader;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天 REST + SSE API（React 前端调用）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;
    private final WorkspaceManager workspaceManager;
    /** sessionId → SSE 连接（确认/恢复继续用同一连接推流） */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public ChatController(AgentService agentService, WorkspaceManager workspaceManager) {
        this.agentService = agentService;
        this.workspaceManager = workspaceManager;
    }

    public record ChatStreamRequest(String workspaceId, String sessionId, String message,
                                    List<AttachmentDto> attachments) {
    }

    public record AttachmentDto(String name, String mimeType, String base64Data) {
    }

    public record ConfirmRequest(String workspaceId, String sessionId, List<String> toolNames, String action) {
    }

    public record StopRequest(String workspaceId, String sessionId) {
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChatController.class);

    /**
     * 流式对话：SSE 推送 StreamEvent（text/reasoning/tool/tool_args/tool_result/subagent/confirm/context/end/error）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest req) {
        log.info("SSE 连接建立: sessionId={}, messageLen={}, atts={}",
                req.sessionId(), req.message() == null ? 0 : req.message().length(),
                req.attachments() == null ? 0 : req.attachments().size());
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(req.sessionId(), emitter);
        emitter.onCompletion(() -> {
            emitters.remove(req.sessionId(), emitter);
            log.info("SSE 连接完成(移除): sessionId={}", req.sessionId());
        });
        emitter.onTimeout(() -> {
            emitters.remove(req.sessionId(), emitter);
            log.warn("SSE 连接超时: sessionId={}", req.sessionId());
        });
        emitter.onError(e -> {
            emitters.remove(req.sessionId(), emitter);
            log.warn("SSE 连接错误: sessionId={}, err={}", req.sessionId(),
                    e == null ? "null" : e.getMessage());
        });

        List<UserAttachment> atts = req.attachments() == null ? List.of()
                : req.attachments().stream()
                        .map(a -> new UserAttachment(a.name(), a.mimeType(), a.base64Data()))
                        .toList();

        try {
            agentService.streamChat(req.workspaceId(), req.sessionId(), req.message(), atts,
                    evt -> safeSend(req.sessionId(), emitter, evt),
                    err -> {
                        log.warn("流式错误: sessionId={}, err={}", req.sessionId(),
                                err == null ? "null" : err.getMessage());
                        safeSend(req.sessionId(), emitter, StreamEvent.error(err.getMessage() == null ? "未知错误" : err.getMessage()));
                        safeComplete(emitter);
                    },
                    () -> {
                        safeSend(req.sessionId(), emitter, StreamEvent.end());
                        safeComplete(emitter);
                    });
        } catch (Exception e) {
            log.error("streamChat 启动失败: sessionId={}, err={}", req.sessionId(), e.getMessage(), e);
            safeSend(req.sessionId(), emitter, StreamEvent.error("启动失败: " + e.getMessage()));
            safeComplete(emitter);
        }
        return emitter;
    }

    /**
     * 工具确认决策：action = once | turn | always | deny
     */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmRequest req) {
        if (req.toolNames() == null || req.toolNames().isEmpty()) {
            return Map.of("ok", false, "reason", "工具列表为空");
        }
        if ("turn".equals(req.action())) {
            agentService.allowTurn(req.sessionId(), req.toolNames());
        } else if ("always".equals(req.action())) {
            agentService.allowPermanently(req.workspaceId(), req.toolNames());
        }
        boolean allowed = !"deny".equals(req.action());
        SseEmitter emitter = emitters.get(req.sessionId());
        if (emitter == null) {
            log.warn("确认时原 SSE 连接已断开: sessionId={}, tools={}", req.sessionId(), req.toolNames());
            // 连接已断：仍恢复 Agent（避免卡死），但事件无处推送，下次发消息可看历史
        }
        agentService.resumeChat(req.workspaceId(), req.sessionId(), allowed,
                evt -> safeSend(req.sessionId(), emitter, evt),
                err -> {
                    log.warn("恢复流错误: sessionId={}, err={}", req.sessionId(),
                            err == null ? "null" : err.getMessage());
                    safeSend(req.sessionId(), emitter, StreamEvent.error(err.getMessage() == null ? "未知错误" : err.getMessage()));
                    safeComplete(emitter);
                },
                () -> {
                    safeSend(req.sessionId(), emitter, StreamEvent.end());
                    safeComplete(emitter);
                });
        return Map.of("ok", true, "action", req.action());
    }

    /**
     * 查询当前挂起的工具确认（前端轮询兜底：SSE confirm 事件丢失时也能弹窗）
     */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam String sessionId) {
        List<Map<String, Object>> tools = agentService.pendingConfirmInfo(sessionId);
        return Map.of("pending", !tools.isEmpty(), "tools", tools);
    }

    /**
     * 停止当前回复（interrupt + dispose）
     */
    @PostMapping("/stop")
    public void stop(@RequestBody StopRequest req) {
        agentService.stopChat(req.workspaceId(), req.sessionId());
    }

    /**
     * 会话历史：从 agent_state.json 解析时序消息
     */
    @GetMapping("/history")
    public List<BoxMessage> history(@RequestParam String workspaceId, @RequestParam String sessionId) {
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) {
            return List.of();
        }
        String userId = ws.getUserId() == null ? AppConstants.DEFAULT_USER_ID : ws.getUserId();
        Path file = ws.getPath().resolve(".easyClaw/agent/state/" + userId + "/" + sessionId + "/agent_state.json");
        return AgentStateBoxReader.read(file);
    }

    private void safeSend(String sessionId, SseEmitter emitter, StreamEvent evt) {
        if (emitter == null) {
            // 原 SSE 连接已断开（客户端关闭/超时）：事件无处推送，静默跳过，Agent 仍正常恢复
            log.warn("SSE emitter 为空(连接已断开?): sessionId={}, type={}",
                    sessionId, evt == null ? "?" : evt.type());
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("message").data(evt));
            if (evt != null && ("confirm".equals(evt.type()) || "error".equals(evt.type()) || "end".equals(evt.type()))) {
                log.info("SSE 已发送事件: sessionId={}, type={}", sessionId, evt.type());
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 发送失败(客户端断开?): sessionId={}, type={}, err={}",
                    sessionId, evt == null ? "?" : evt.type(), e.getMessage());
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
