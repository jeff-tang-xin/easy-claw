package com.xinl.easyclaw.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.AgentStateBoxReader;
import com.xinl.easyclaw.agent.SessionTranscriptStore;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
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
    private final ObjectMapper objectMapper;
    private final com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties;
    private final WorkspaceAccessGuard accessGuard;
    private final ToolConfirmValidator toolConfirmValidator;
    private final com.xinl.easyclaw.workspace.WorkspaceFileLayout workspaceFileLayout;
    /** sessionId → SSE 连接（确认/恢复继续用同一连接推流） */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public ChatController(AgentService agentService, WorkspaceManager workspaceManager, ObjectMapper objectMapper,
                          com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties,
                          WorkspaceAccessGuard accessGuard,
                          ToolConfirmValidator toolConfirmValidator,
                          com.xinl.easyclaw.workspace.WorkspaceFileLayout workspaceFileLayout) {
        this.agentService = agentService;
        this.workspaceManager = workspaceManager;
        this.objectMapper = objectMapper;
        this.agentScopeProperties = agentScopeProperties;
        this.accessGuard = accessGuard;
        this.toolConfirmValidator = toolConfirmValidator;
        this.workspaceFileLayout = workspaceFileLayout;
    }

    public record ChatStreamRequest(String workspaceId, String sessionId, String message,
                                    List<AttachmentDto> attachments, String skillName) {
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
        // 归属校验必须在建立 emitter 之前：否则非法请求也会占用连接槽位与 emitters 表项。
        // 新会话允许尚未落库（首条消息时才创建），故 requireExisting=false
        accessGuard.checkSession(req.workspaceId(), req.sessionId(), false);

        log.info("SSE 连接建立: sessionId={}, messageLen={}, atts={}",
                req.sessionId(), req.message() == null ? 0 : req.message().length(),
                req.attachments() == null ? 0 : req.attachments().size());

        // 并发上限：SSE 是长连接，不限流则单个客户端可反复调 /stream 把容器工作线程与
        // Agent 会话状态吃满（每条连接背后都挂着一个 Agent 回合和一份会话内存）
        int maxConns = agentScopeProperties.getAgent().getMaxSseConnections();
        if (maxConns > 0 && !emitters.containsKey(req.sessionId()) && emitters.size() >= maxConns) {
            log.warn("SSE 连接数达上限: current={}, max={}, 拒绝 sessionId={}",
                    emitters.size(), maxConns, req.sessionId());
            throw new ApiExceptions.BadRequestException(
                    "服务器并发会话已达上限（" + maxConns + "），请稍后重试");
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs());
        // 同 sessionId 重复 /stream：旧连接已失去意义，主动收尾避免泄漏
        SseEmitter previous = emitters.put(req.sessionId(), emitter);
        if (previous != null) {
            log.warn("SSE 同会话重复连接，关闭旧连接: sessionId={}", req.sessionId());
            safeComplete(previous);
        }
        emitter.onCompletion(() -> {
            emitters.remove(req.sessionId(), emitter);
            log.info("SSE 连接完成(移除): sessionId={}", req.sessionId());
        });
        emitter.onTimeout(() -> {
            emitters.remove(req.sessionId(), emitter);
            log.warn("SSE 连接超时: sessionId={}", req.sessionId());
            // 连接已死，后端回合失去唯一出口：主动中止并清理，否则订阅与会话状态常驻
            agentService.stopChat(req.workspaceId(), req.sessionId());
        });
        emitter.onError(e -> {
            emitters.remove(req.sessionId(), emitter);
            log.warn("SSE 连接错误: sessionId={}, err={}", req.sessionId(),
                    e == null ? "null" : e.getMessage());
            agentService.stopChat(req.workspaceId(), req.sessionId());
        });

        List<UserAttachment> atts = req.attachments() == null ? List.of()
                : req.attachments().stream()
                        .map(a -> new UserAttachment(a.name(), a.mimeType(), a.base64Data()))
                        .toList();

        // 入口即拒超量附件：base64 会整体进内存并拼入消息，放过去等于把堆交给调用方支配
        String attErr = validateAttachments(atts);
        if (attErr != null) {
            log.warn("附件校验失败: sessionId={}, reason={}", req.sessionId(), attErr);
            safeSend(req.sessionId(), emitter, StreamEvent.error(attErr));
            emitter.complete();
            return emitter;
        }

        try {
            String skillName = req.skillName() == null || req.skillName().isBlank() ? null : req.skillName();
            agentService.streamChat(req.workspaceId(), req.sessionId(), req.message(), atts,
                    skillName,
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
        accessGuard.checkSession(req.workspaceId(), req.sessionId(), false);
        if (req.toolNames() == null || req.toolNames().isEmpty()) {
            return Map.of("ok", false, "reason", "工具列表为空");
        }
        // 工具名白名单：allowPermanently 会把名字写成永久规则，放过任意字符串
        // 等于让调用方预埋免确认条目（详见 ToolConfirmValidator 类注释）
        List<String> unknown = toolConfirmValidator.rejectUnknown(req.sessionId(), req.toolNames());
        if (!unknown.isEmpty()) {
            log.warn("确认请求含未知工具: sessionId={}, unknown={}", req.sessionId(), unknown);
            return Map.of("ok", false, "reason", "未知工具: " + unknown);
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
        // pending 无 workspaceId 参数，至少做格式校验挡住畸形 ID（返回内容不含跨会话数据）
        accessGuard.checkIdFormat("sessionId", sessionId);
        List<Map<String, Object>> tools = agentService.pendingConfirmInfo(sessionId);
        return Map.of("pending", !tools.isEmpty(), "tools", tools);
    }

    /**
     * 停止当前回复（interrupt + dispose）
     */
    @PostMapping("/stop")
    public void stop(@RequestBody StopRequest req) {
        accessGuard.checkSession(req.workspaceId(), req.sessionId(), false);
        agentService.stopChat(req.workspaceId(), req.sessionId());
    }

    /**
     * 会话运行状态：重连/刷新后前端用来恢复 running / pending 状态
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String workspaceId, @RequestParam String sessionId) {
        accessGuard.checkSession(workspaceId, sessionId, false);
        return agentService.getSessionStatus(sessionId);
    }

    /**
     * 会话历史：优先读 append-only 转录（transcript.jsonl，不随上下文压缩丢失），
     * 无转录的旧会话回退解析 agent_state.json（模型上下文，压缩后旧消息会被摘要替换）。
     */
    @GetMapping("/history")
    public List<BoxMessage> history(@RequestParam String workspaceId, @RequestParam String sessionId) {
        // history 直接用 sessionId 拼文件系统路径，必须先过字符白名单 + 归属校验，
        // 否则可读取任意工作区任意会话的完整对话记录
        WorkspaceContext ws = accessGuard.checkSession(workspaceId, sessionId, false);
        if (ws == null) {
            return List.of();
        }
        Path sessionDir = workspaceFileLayout.sessionStateDir(
                ws.getPath(), ws.getUserId(), sessionId);
        List<BoxMessage> fromTranscript = SessionTranscriptStore.read(sessionDir);
        if (!fromTranscript.isEmpty()) {
            return fromTranscript;
        }
        return AgentStateBoxReader.read(
                sessionDir.resolve(com.xinl.easyclaw.workspace.WorkspaceFileLayout.AGENT_STATE_FILE));
    }

    /** SSE 超时毫秒数；配置 &lt;=0 时退化为「永不超时」（不推荐，仅为兼容旧配置保留） */
    private long sseTimeoutMs() {
        int minutes = agentScopeProperties.getAgent().getSseTimeoutMinutes();
        return minutes > 0 ? minutes * 60_000L : 0L;
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

    private void safeSend(String sessionId, SseEmitter emitter, StreamEvent evt) {
        if (emitter == null) {
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
        }
    }
}
