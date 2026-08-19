package com.xinl.easyclaw.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.workspace.entity.SessionMessageEntity;
import com.xinl.easyclaw.workspace.repository.SessionMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentService agentService;
    private final SessionMessageRepository messageRepo;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public ChatController(AgentService agentService, SessionMessageRepository messageRepo) {
        this.agentService = agentService;
        this.messageRepo = messageRepo;
    }

    public record ChatStreamRequest(String workspaceId, String sessionId, String message,
                                    List<AttachmentDto> attachments, String skillName) {}
    public record AttachmentDto(String name, String mimeType, String base64Data) {}
    public record ConfirmRequest(String workspaceId, String sessionId, List<String> toolNames, String action) {}
    public record StopRequest(String workspaceId, String sessionId) {}

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(req.sessionId(), emitter);
        emitter.onCompletion(() -> emitters.remove(req.sessionId(), emitter));
        emitter.onTimeout(() -> emitters.remove(req.sessionId(), emitter));
        emitter.onError(e -> emitters.remove(req.sessionId(), emitter));

        List<UserAttachment> atts = req.attachments() == null ? List.of()
                : req.attachments().stream()
                        .map(a -> new UserAttachment(a.name(), a.mimeType(), a.base64Data()))
                        .toList();

        try {
            agentService.streamChat(req.workspaceId(), req.sessionId(), req.message(), atts,
                    req.skillName(),
                    evt -> safeSend(emitter, evt),
                    err -> {
                        safeSend(emitter, StreamEvent.error(err.getMessage() == null ? "未知错误" : err.getMessage()));
                        safeComplete(emitter);
                    },
                    () -> {
                        safeSend(emitter, StreamEvent.end());
                        safeComplete(emitter);
                    });
        } catch (Exception e) {
            safeSend(emitter, StreamEvent.error("启动失败: " + e.getMessage()));
            safeComplete(emitter);
        }
        return emitter;
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmRequest req) {
        if ("always".equals(req.action())) {
            agentService.allowPermanently(req.workspaceId(), req.toolNames());
        }
        boolean allowed = !"deny".equals(req.action());
        agentService.resumeChat(req.workspaceId(), req.sessionId(), allowed,
                evt -> {}, err -> {}, () -> {});
        return Map.of("ok", true, "action", req.action());
    }

    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam String sessionId) {
        return Map.of("pending", false, "tools", List.of());
    }

    @PostMapping("/stop")
    public void stop(@RequestBody StopRequest req) {
        agentService.stopChat(req.workspaceId(), req.sessionId());
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String workspaceId, @RequestParam String sessionId) {
        return agentService.getSessionStatus(sessionId);
    }

    @GetMapping("/history")
    public List<BoxMessage> history(@RequestParam String workspaceId, @RequestParam String sessionId) {
        List<SessionMessageEntity> entities = messageRepo.findBySessionIdOrderBySeqAsc(sessionId);
        List<BoxMessage> result = new ArrayList<>(entities.size());
        for (SessionMessageEntity e : entities) {
            try {
                BoxMessage.Type type;
                try {
                    type = BoxMessage.Type.valueOf(e.getType());
                } catch (IllegalArgumentException iae) {
                    log.warn("跳过未知消息类型: sessionId={}, seq={}, type={}", sessionId, e.getSeq(), e.getType());
                    continue;
                }
                BoxMessage msg = new BoxMessage(type, e.getSeq());
                msg.setContent(e.getContent());
                msg.setToolName(e.getToolName());
                msg.setToolArgs(e.getToolArgs());
                msg.setToolResult(e.getToolResult());
                msg.setSubagentName(e.getSubagentName());
                msg.setImages(parseImages(e.getImagesJson()));
                if (e.getCreatedAt() != null) {
                    msg.setTimestamp(e.getCreatedAt().toEpochMilli());
                }
                result.add(msg);
            } catch (Exception ex) {
                log.warn("解析历史消息失败: sessionId={}, seq={}, err={}", sessionId, e.getSeq(), ex.getMessage());
            }
        }
        log.debug("history: sessionId={}, workspaceId={}, total={}", sessionId, workspaceId, result.size());
        return result;
    }

    private List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) return List.of();
        try {
            return MAPPER.readValue(imagesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void safeSend(SseEmitter emitter, StreamEvent evt) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("message").data(evt));
        } catch (IOException | IllegalStateException ignored) {}
    }

    private void safeComplete(SseEmitter emitter) {
        try { emitter.complete(); } catch (Exception ignored) {}
    }
}
