package com.xinl.easyclaw.middleware;

import com.xinl.easyclaw.agent.SessionRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 工具连续失败护栏 —— 通过 {@link AgentEventEmitter} 推送 {@code tool_fail_guard} 事件。
 *
 * <p><b>职责</b>：同一工具在同一会话中连续失败达到阈值时，说明模型陷入了无效重试
 * （典型场景：路径不存在还反复读、参数格式错还反复调）。本 middleware 会发射
 * {@link CustomEvent}，由 {@code AgentService} 的 {@code CUSTOM} 分支翻译为
 * {@code StreamEvent.context(...)} 提示，让模型停手。
 *
 * <p><b>计数为什么可以直接写 {@link SessionRegistry}</b>：旧实现
 * （{@code AgentService} 事件消费侧）已在同一提交内删除，本 middleware 是失败计数的
 * 唯一写入方，不存在同一次失败被记两次、护栏在半数阈值处误触发的问题。
 *
 * <p><b>为什么挂 onActing</b>：{@link MiddlewareBase} 只有 5 个钩子、
 * 没有专门的 onToolResult。工具相关的横切逻辑只能挂 onActing，在返回流上
 * 用 {@code doOnNext} 观察 {@link ToolResultEndEvent}。
 */
public class ToolFailGuard implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolFailGuard.class);

    /** CustomEvent 的 name，前端据此路由。 */
    public static final String EVENT_NAME = "tool_fail_guard";

    private final SessionRegistry sessions;

    private final int maxConsecutiveFailures;

    /**
     * @param sessions               会话级失败计数的存放处
     * @param maxConsecutiveFailures 阈值，来自 {@code agentscope.agent.max-consecutive-tool-failures}；
     *                               小于等于 0 表示关闭护栏
     */
    public ToolFailGuard(SessionRegistry sessions, int maxConsecutiveFailures) {
        this.sessions = sessions;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        if (maxConsecutiveFailures <= 0) {
            return next.apply(input);
        }

        // deferContextual 取 AgentEventEmitter：与 FileChangeMiddleware 相同模式。
        // emitter 缺席（非流式 call() 路径）时，护栏仍统计失败计数但只记日志不发事件，
        // 不会影响工具执行。
        return Flux.deferContextual(cv -> {
            AgentEventEmitter emitter = AgentEventEmitter.fromContext(cv).orElse(null);
            return next.apply(input)
                    .doOnNext(evt -> {
                        if (evt instanceof ToolResultEndEvent end) {
                            trackOutcome(ctx.getSessionId(), end, emitter);
                        }
                    });
        });
    }

    /**
     * 记录单次工具执行的成败，阈值触发时发射 {@link CustomEvent}。
     *
     * <p>成功时清零计数：这是「连续」失败语义的关键。少了 reset，计数会变成
     * 「累计失败」，一个偶发失败几次的工具早晚会误触发护栏。
     */
    private void trackOutcome(String sessionId, ToolResultEndEvent end,
                              AgentEventEmitter emitter) {
        String toolName = end.getToolCallName();
        if (sessionId == null || toolName == null) {
            return;
        }
        boolean failed = end.getState() == ToolResultState.ERROR;

        if (!failed) {
            sessions.resetToolFailure(sessionId, toolName);
            return;
        }

        int fails = sessions.recordToolFailure(sessionId, toolName);
        if (fails >= maxConsecutiveFailures) {
            log.warn("工具连续失败护栏触发: session={}, tool={}, fails={}",
                    sessionId, toolName, fails);
            if (emitter != null) {
                emitter.emit(new CustomEvent(EVENT_NAME, Map.of(
                        "tool", toolName,
                        "fails", fails,
                        "message", "工具[" + toolName + "] 连续失败 " + fails
                                + " 次，请停止重试，检查参数/路径/权限后向用户说明并询问。"
                )));
            }
        }
    }
}
