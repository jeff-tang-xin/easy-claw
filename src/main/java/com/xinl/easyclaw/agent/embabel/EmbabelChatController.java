package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Embabel REST 控制器
 * <p>
 * 端点：
 * - POST /api/embabel/chat       → 同步执行 Agent GOAP 规划，返回 ChatResult
 * - POST /api/embabel/chat/stream → SSE 流式：Agent 生命周期事件 (plan/action/done)
 * - GET  /api/embabel/agents     → 列出已注册 Agent
 */
@RestController
@RequestMapping("/api/embabel")
public class EmbabelChatController {

    private static final Logger log = LoggerFactory.getLogger(EmbabelChatController.class);

    private final AgentPlatform agentPlatform;
    private final RoleAgentFactory roleFactory;
    private final AgentProcessEventBridge eventBridge;

    public EmbabelChatController(AgentPlatform agentPlatform,
                                 RoleAgentFactory roleFactory,
                                 AgentProcessEventBridge eventBridge) {
        this.agentPlatform = agentPlatform;
        this.roleFactory = roleFactory;
        this.eventBridge = eventBridge;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResult chat(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String roleName = request.getOrDefault("role", "");
        log.info("Embabel chat: role={}, content={}...",
                roleName, content.substring(0, Math.min(50, content.length())));

        var agent = roleFactory.resolveAgent(roleName);
        if (agent == null) {
            return new ChatResult("未找到角色对应的 Agent: " + roleName, List.of(), roleName);
        }

        AgentProcess process = agentPlatform.runAgentFrom(agent, ProcessOptionsFactory.forRoot(),
                Map.of("input", new UserInput(content)));

        ChatResult result = process.resultOfType(ChatResult.class);
        return result != null ? result : new ChatResult("执行完成", List.of(), roleName);
    }

    /**
     * SSE 流式输出（生命周期事件）
     * <p>
     * 先通过 EventBridge 注册监听 → 创建 AgentProcess → 绑定 processId ↔ sessionId →
     * 异步 start → 将 ProcessLifecycleEvent 映射为 SSE 文本帧。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String roleName = request.getOrDefault("role", "");
        log.info("Embabel stream chat: role={}, content={}...",
                roleName, content.substring(0, Math.min(40, content.length())));

        var agent = roleFactory.resolveAgent(roleName);
        if (agent == null) {
            return Flux.just("event: error\ndata: Agent not found: " + roleName + "\n\n");
        }

        String sessionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        eventBridge.register(sessionId, evt -> {
            String dataStr = switch (evt.type()) {
                case PLAN_FORMULATED -> "plan_formulated";
                case ACTION_START -> "action_start:" + (evt.data() != null ? evt.data() : "");
                case ACTION_END -> "action_end:" + (evt.data() != null ? evt.data() : "");
                case TOOL_CALL_START -> "tool_call_start:" + (evt.data() != null ? evt.data() : "");
                case TOOL_CALL_END -> "tool_call_end:" + (evt.data() != null ? evt.data() : "");
                case LLM_CALL_START -> "llm_call_start:" + (evt.data() != null ? evt.data() : "");
                case LLM_CALL_END -> "llm_call_end:" + (evt.data() != null ? evt.data() : "");
                case PAUSED -> "paused";
                case WAITING -> "waiting";
                case STUCK -> "stuck:" + (evt.data() != null ? evt.data() : "进程执行卡住");
                case COMPLETED -> {
                    Object result = evt.data();
                    if (result instanceof ChatResult cr) {
                        yield "completed:" + (cr.reply() != null ? cr.reply() : "");
                    }
                    yield "completed";
                }
                case FAILED -> "failed:" + (evt.data() != null ? evt.data() : "");
                case LLM_INVOCATION -> "llm_invocation:" + (evt.data() != null ? evt.data() : "");
                case LLM_THINKING -> "llm_thinking:" + (evt.data() != null ? evt.data() : "");
                case PROGRESS_UPDATE -> "progress_update:" + (evt.data() != null ? evt.data() : "");
                case GOAL_ACHIEVED -> "goal_achieved:" + (evt.data() != null ? evt.data() : "");
                case TOOL_LOOP_START -> "tool_loop_start:" + (evt.data() != null ? evt.data() : "");
                case TOOL_LOOP_COMPLETED -> "tool_loop_completed:" + (evt.data() != null ? evt.data() : "");
                case STATE_TRANSITION -> "state_transition:" + (evt.data() != null ? evt.data() : "");
                case REPLAN_REQUESTED -> "replan_requested:" + (evt.data() != null ? evt.data() : "");
            };
            String frame = "event: " + evt.type().name().toLowerCase()
                    + "\ndata: " + dataStr
                    + "\n\n";
            sink.tryEmitNext(frame);
            if (evt.type() == ProcessLifecycleEvent.Type.COMPLETED
                    || evt.type() == ProcessLifecycleEvent.Type.STUCK
                    || evt.type() == ProcessLifecycleEvent.Type.FAILED) {
                sink.tryEmitComplete();
                eventBridge.unregister(sessionId);
            }
        });

        try {
            AgentProcess process = agentPlatform.createAgentProcessFrom(agent, ProcessOptionsFactory.forRoot(),
                    new UserInput(content));
            eventBridge.bindProcess(sessionId, process.getId());
            agentPlatform.start(process);
            log.info("AgentProcess 已启动: id={}, session={}", process.getId(), sessionId);
        } catch (Exception e) {
            log.error("启动 AgentProcess 失败", e);
            eventBridge.unregister(sessionId);
            return Flux.just("event: error\ndata: " + e.getMessage() + "\n\n");
        }

        return sink.asFlux()
                .timeout(Duration.ofMinutes(10))
                .doOnError(e -> log.error("Stream error: {}", e.getMessage()))
                .doOnCancel(() -> eventBridge.unregister(sessionId));
    }

    @GetMapping("/agents")
    public List<Map<String, String>> listAgents() {
        return agentPlatform.agents().stream()
                .map(a -> Map.of(
                        "name", a.getName(),
                        "class", a.getClass().getSimpleName()
                ))
                .toList();
    }
}
