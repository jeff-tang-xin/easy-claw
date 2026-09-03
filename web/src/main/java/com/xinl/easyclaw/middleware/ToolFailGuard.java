package com.xinl.easyclaw.middleware;

import com.xinl.easyclaw.agent.SessionRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 工具连续失败护栏 —— <b>影子模式</b>，当前不注入停止提示。
 *
 * <p><b>职责</b>：同一工具在同一会话中连续失败达到阈值时，说明模型陷入了无效重试
 * （典型场景：路径不存在还反复读、参数格式错还反复调）。旧实现
 * {@code AgentService:1985-1999} 会注入一条 context 提示让模型停手。
 *
 * <p><b>为什么是影子模式</b>：旧实现仍在运行，重复注入会让模型连收两条同样的停止提示，
 * 徒增 token 且可能触发过度反应。此处只记 WARN 日志供比对，见
 * {@code docs/refactor-plan.md:391} 的并行降险策略。
 *
 * <p><b>影子期不碰 SessionRegistry</b>：旧实现每次失败已经调用一次
 * {@code recordToolFailure}，middleware 若再调一次，同一次失败会被记两次，护栏将在
 * 半数阈值处误触发——那不是零行为变更。因此影子期用本类私有计数表独立统计，
 * 切换（{@link #SHADOW_MODE} 置 false + 摘除旧实现）时再改为写 {@code SessionRegistry}。
 */
public class ToolFailGuard implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolFailGuard.class);

    /** 见 {@link FileChangeMiddleware#SHADOW_MODE} 的切换约束说明 */
    private static final boolean SHADOW_MODE = true;

    /**
     * 影子期计数表上限。middleware 实例随 agent 长期存活，会话只增不减会泄漏；
     * 影子期只为比对日志，超过上限直接停止统计比无限增长安全。
     */
    private static final int MAX_SHADOW_SESSIONS = 256;

    /** 影子期私有计数：sessionId -> (toolName -> 连续失败次数) */
    private final Map<String, Map<String, Integer>> shadowFailCounts = new ConcurrentHashMap<>();

    private final SessionRegistry sessions;

    private final int maxConsecutiveFailures;

    /**
     * @param sessions               会话级失败计数的存放处，切换后由本类接管写入
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

        return next.apply(input)
                .doOnNext(evt -> {
                    if (evt instanceof ToolResultEndEvent end) {
                        trackOutcome(ctx.getSessionId(), end);
                    }
                });
    }

    /**
     * 记录单次工具执行的成败。
     *
     * <p><b>成功必须清零</b>：这是「连续」失败语义的关键。少了 reset，计数会变成
     * 「累计失败」，一个偶发失败几次的工具早晚会误触发护栏。
     */
    private void trackOutcome(String sessionId, ToolResultEndEvent end) {
        String toolName = end.getToolCallName();
        if (sessionId == null || toolName == null) {
            return;
        }
        boolean failed = end.getState() == ToolResultState.ERROR;

        if (!SHADOW_MODE) {
            if (!failed) {
                sessions.resetToolFailure(sessionId, toolName);
                return;
            }
            int fails = sessions.recordToolFailure(sessionId, toolName);
            if (fails >= maxConsecutiveFailures) {
                log.warn("工具连续失败护栏触发: session={}, tool={}, fails={}",
                        sessionId, toolName, fails);
            }
            return;
        }

        int fails = trackShadow(sessionId, toolName, failed);
        if (failed && fails >= maxConsecutiveFailures) {
            log.warn("[shadow] 工具连续失败护栏命中: session={}, tool={}, fails={}",
                    sessionId, toolName, fails);
        }
    }

    /** 影子期私有计数，返回本工具当前连续失败次数（成功时为 0） */
    private int trackShadow(String sessionId, String toolName, boolean failed) {
        if (!failed) {
            Map<String, Integer> counts = shadowFailCounts.get(sessionId);
            if (counts != null) {
                counts.remove(toolName);
            }
            return 0;
        }
        if (!shadowFailCounts.containsKey(sessionId)
                && shadowFailCounts.size() >= MAX_SHADOW_SESSIONS) {
            return 0;
        }
        return shadowFailCounts
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .merge(toolName, 1, Integer::sum);
    }
}
