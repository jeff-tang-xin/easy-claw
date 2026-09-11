package com.xinl.easyclaw.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 介入消息（插队）注入 —— 以 USER 消息在本推理步注入，替代 vendored InboxMiddleware 的失效路径。
 *
 * <p><b>为什么重写</b>：vendored {@code InboxMiddleware} 在每个推理步前 drain 收件箱，但只把消息
 * 注入 state（下一推理步才进模型输入）——当前模型调用的 input 在 middleware 链运行之前已固化
 * （{@code ReActAgent.reasoning} 先 {@code prependSystemMsg} 快照、后跑链），且注入角色是
 * ASSISTANT（{@code injectHintsToContext}）。后果：当前步直接收尾时介入永远丢失，且介入内容
 * 以「模型自己说的话」残留在历史中（角色归因错误）。
 *
 * <p><b>本类的注入方式</b>：drain 到的介入内容合并为一条 {@link MsgRole#USER} 消息，同时进两处：
 * <ol>
 *   <li>重建 {@link ReasoningInput} 传入 {@code next}（vendored {@code TaskReminderMiddleware} 同款
 *       手法）——本次模型调用立即可见，即在「上一迭代结束、本次思考发起前」的空挡生效；</li>
 *   <li>{@code state.contextMutable()}——持久化，后续轮次与会话恢复后仍可见。</li>
 * </ol>
 *
 * <p><b>与自动装配的 vendored InboxMiddleware 的关系</b>：{@code HarnessAgent.build()} 在
 * messageBus 非空时必然装配 vendored 版（{@code HarnessAgent:2479}），但 build() 晚于所有
 * Builder 阶段的 {@code .middleware(...)} 注册，责任链上本类在前。drain 是消耗性的，本类取走后
 * vendored 版恒 drain 到空队列、原样传递（Easy-Claw 未启用 asyncToolTimeout，其 stale 分支
 * 亦恒空），不存在双注入。
 *
 * <p><b>为什么 USER 角色安全</b>：drain 发生在推理步前，此时 context 末尾是工具结果或用户消息；
 * {@code [tool_result] → [user 介入] → [assistant]} 是 DeepSeek/OpenAI 合法序列，语义正是
 * 「工具执行完，用户补充了新指示」。多条介入合并为一条，避免连续同角色。
 *
 * <p><b>投递侧</b>：{@code AgentService.interveneTurn} → {@code MessageBus.inboxPush}
 * （payload 含 {@code hint}/{@code source}），本类只消费 {@code hint} 文本。
 */
public class InterventionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(InterventionMiddleware.class);

    /** 与 vendored InboxMiddleware 的单次 drain 上限对齐。 */
    private static final int MAX_DRAIN_COUNT = 100;

    private final MessageBus messageBus;

    public InterventionMiddleware(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return next.apply(input);
        }
        return messageBus.inboxDrain(sessionId, MAX_DRAIN_COUNT)
                .flatMapMany(entries -> {
                    List<String> hints = new ArrayList<>();
                    for (BusEntry entry : entries) {
                        Object hint = entry.payload().get("hint");
                        if (hint != null && !hint.toString().isBlank()) {
                            hints.add(hint.toString());
                        }
                    }
                    if (hints.isEmpty()) {
                        return next.apply(input);
                    }
                    Msg intervention = Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .textContent(String.join("\n", hints))
                            .build();
                    // ① 当前步生效：重建 ReasoningInput（TaskReminderMiddleware 同款手法）
                    List<Msg> messages = new ArrayList<>(input.messages());
                    messages.add(intervention);
                    ReasoningInput effective =
                            new ReasoningInput(messages, input.tools(), input.options());
                    // ② 持久化：进 state，后续轮次与会话恢复后仍可见
                    AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                    if (state != null) {
                        state.contextMutable().add(intervention);
                    }
                    log.info("介入消息已注入当前推理步: session={}, {} 条", sessionId, hints.size());
                    return next.apply(effective);
                })
                // 介入通道故障不能弄死推理：drain 失败按无介入继续
                .onErrorResume(e -> {
                    log.warn("介入收件箱 drain 失败，按无介入继续: session={}, err={}",
                            sessionId, e.toString());
                    return next.apply(input);
                });
    }
}
