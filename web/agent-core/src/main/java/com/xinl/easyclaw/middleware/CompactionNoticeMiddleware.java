package com.xinl.easyclaw.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * 上下文压缩感知 —— 压缩生效后向 UI 推送 {@code compaction} 提示。
 *
 * <p><b>为什么存在</b>：vendored {@code ConversationCompactor} 触发压缩时只写 DEBUG 日志，
 * 界面上表现为「AI 忽然对早前提过的事毫无印象」，用户无从得知发生过什么。本类在压缩
 * 生效后推送一条可见提示，说明旧消息已被折叠为摘要。
 *
 * <p><b>检测原理</b>：压缩生效后，会话上下文被替换为「1 条摘要消息 + 最近 tail」，摘要
 * 消息的 name 是 vendored 公开常量 {@link ConversationCompactor#SUMMARY_MSG_NAME}，id 由
 * 摘要内容的 UUID 派生（再次压缩内容变、id 亦变）。本类挂 {@code onReasoning}，逐帧比对
 * 推理输入中摘要消息的 id：
 * <ul>
 *   <li>首帧只登记不通知——会话恢复 / rebuildAgent 后输入里可能已有旧摘要，属存量而非
 *       本实例存活期内的新压缩；</li>
 *   <li>之后摘要 id 从「无/旧」变为「新」，即判定发生了一次压缩，发事件并更新登记。</li>
 * </ul>
 * 注意时序：压缩由责任链下游的 vendored CompactionMiddleware 在同一推理步内执行，本类
 * 在其上游，当帧检测不到；提示在压缩后的下一个推理步到达 UI（此时摘要生成的 LLM 调用
 * 刚结束，用户恰好看到「正在思考」之后的这条说明）。
 *
 * <p><b>发射通道</b>：与 {@code FileChangeMiddleware} 相同，经 {@link AgentEventEmitter}
 * 发 {@link CustomEvent}，由 {@code CustomEventTranslator} 翻译为前端协议事件，并由
 * {@code TranscriptRecorder} 落盘为 SYSTEM 消息（刷新/回放后仍可见）。emitter 缺席
 * （非流式路径）时静默跳过——提示是 UI 增强，不能影响推理。
 */
public class CompactionNoticeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CompactionNoticeMiddleware.class);

    /** CustomEvent 的 name，前端据此路由。 */
    public static final String EVENT_NAME = "compaction";

    /** 「无摘要」的占位登记值（消息 id 不可能为空串，不会与真实 id 冲突）。 */
    private static final String NO_SUMMARY = "";

    /**
     * 已登记（已通知或判定为存量）的摘要消息 id；{@code null} 表示本实例还未见过任何
     * 推理帧。一个 HarnessAgent 一个实例，推理帧在 ReAct 循环中串行到达，AtomicReference
     * 仅为防御并发订阅。
     */
    private final AtomicReference<String> registeredSummaryId = new AtomicReference<>(null);

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // deferContextual：AgentEventEmitter 存放在 Reactor Context 里，只有订阅时才能拿到
        // （FileChangeMiddleware 同款手法）。
        return Flux.deferContextual(cv -> {
            try {
                noticeIfCompacted(cv, input);
            } catch (Exception e) {
                // 提示通道绝不能弄死推理
                log.debug("compaction 提示跳过: {}", e.toString());
            }
            return next.apply(input);
        });
    }

    private void noticeIfCompacted(ContextView cv, ReasoningInput input) {
        String summaryId = findSummaryId(input == null ? null : input.messages());
        String registered = registeredSummaryId.get();
        if (registered == null) {
            // 首帧：登记存量（含「无摘要」），不通知
            registeredSummaryId.compareAndSet(null, summaryId == null ? NO_SUMMARY : summaryId);
            return;
        }
        if (summaryId == null || summaryId.equals(registered)) {
            return;
        }
        // 摘要 id 变化 = 本实例存活期内发生了一次压缩
        if (!registeredSummaryId.compareAndSet(registered, summaryId)) {
            return;
        }
        AgentEventEmitter emitter = AgentEventEmitter.fromContext(cv).orElse(null);
        if (emitter == null) {
            log.debug("compaction 提示跳过（当前调用链无 AgentEventEmitter）");
            return;
        }
        int keeping = input.messages() == null ? 0 : input.messages().size();
        log.info("检测到上下文压缩，向 UI 推送提示: 压缩后上下文 {} 条消息", keeping);
        emitter.emit(new CustomEvent(EVENT_NAME, Map.of("keeping", keeping)));
    }

    /** 摘要消息在压缩后的上下文中至多一条（再次压缩会把旧摘要折叠进新摘要）。 */
    private String findSummaryId(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (Msg m : messages) {
            if (m != null && ConversationCompactor.SUMMARY_MSG_NAME.equals(m.getName())) {
                return m.getId();
            }
        }
        return null;
    }
}
