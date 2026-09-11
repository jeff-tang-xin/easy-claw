package com.xinl.easyclaw.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * InterventionMiddleware 单测：验证介入消息以 USER 角色注入当前推理步 input 与 state，
 * 以及空收件箱 / 无会话 / drain 故障时的原样透传。
 */
class InterventionMiddlewareTest {

    private static BusEntry entry(String hint) {
        return new BusEntry("e-1", Map.of("hint", hint, "source", "user"));
    }

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static ReasoningInput inputWith(Msg... msgs) {
        return new ReasoningInput(new ArrayList<>(List.of(msgs)), List.of(), null);
    }

    private static Function<ReasoningInput, Flux<AgentEvent>> capturingNext(
            AtomicReference<ReasoningInput> captured) {
        return ri -> {
            captured.set(ri);
            return Flux.empty();
        };
    }

    @Test
    @DisplayName("介入消息合并为一条 USER 消息，同时注入当前 input 与 state，原 input 不被修改")
    void injectsInterventionIntoCurrentInputAndState() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain("sess-1", 100))
                .thenReturn(Mono.just(List.of(entry("先别删文件"), entry("改成重命名"))));

        List<Msg> stateContext = new ArrayList<>();
        AgentState state = mock(AgentState.class);
        when(state.contextMutable()).thenReturn(stateContext);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("sess-1").build();
        ctx.setAgentState(state);

        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("原始任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        mw.onReasoning(mock(Agent.class), ctx, input, capturingNext(captured)).blockLast();

        ReasoningInput effective = captured.get();
        assertEquals(2, effective.messages().size(), "原消息 + 一条介入消息");
        Msg injected = effective.messages().get(1);
        assertEquals(MsgRole.USER, injected.getRole(), "介入必须以 USER 角色注入");
        assertEquals("先别删文件\n改成重命名", injected.getTextContent(), "多条介入按序合并");
        assertEquals(1, input.messages().size(), "原 input 不可被修改（重建而非追加）");
        assertEquals(1, stateContext.size(), "state 必须持久化同一介入消息");
        assertEquals("先别删文件\n改成重命名", stateContext.get(0).getTextContent());
    }

    @Test
    @DisplayName("空收件箱：input 原样透传（同一引用），不触碰 state")
    void emptyInboxPassesThrough() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));

        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        mw.onReasoning(null, RuntimeContext.builder().sessionId("sess-1").build(), input,
                capturingNext(captured)).blockLast();

        assertSame(input, captured.get(), "空收件箱必须原样透传");
    }

    @Test
    @DisplayName("无 sessionId：零 drain、原样透传")
    void blankSessionSkipsDrain() {
        MessageBus bus = mock(MessageBus.class);
        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        mw.onReasoning(null, RuntimeContext.empty(), input, capturingNext(captured)).blockLast();

        assertSame(input, captured.get());
        verifyNoInteractions(bus);
    }

    @Test
    @DisplayName("drain 故障：按无介入继续推理，不中断流")
    void drainFailureFallsBackToPassthrough() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain(anyString(), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("inbox down")));

        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        mw.onReasoning(null, RuntimeContext.builder().sessionId("sess-1").build(), input,
                capturingNext(captured)).blockLast();

        assertSame(input, captured.get(), "drain 失败必须原样透传，不能弄死推理");
    }

    @Test
    @DisplayName("payload 无 hint 的条目被跳过；全部无 hint 视同空收件箱")
    void entriesWithoutHintAreSkipped() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of(
                new BusEntry("e-2", Map.of("source", "user")),
                new BusEntry("e-3", Map.of("hint", "  ", "source", "user")))));

        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        mw.onReasoning(null, RuntimeContext.builder().sessionId("sess-1").build(), input,
                capturingNext(captured)).blockLast();

        assertSame(input, captured.get(), "无有效 hint 必须原样透传");
    }

    @Test
    @DisplayName("ctx 无 state 时仍注入当前 input（持久化降级跳过）")
    void injectsInputEvenWithoutState() {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain("sess-1", 100)).thenReturn(Mono.just(List.of(entry("追加要求"))));

        InterventionMiddleware mw = new InterventionMiddleware(bus);
        ReasoningInput input = inputWith(userMsg("任务"));
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        // ctx 不 setAgentState、agent 为 null -> resolveAgentState 返回 null
        mw.onReasoning(null, RuntimeContext.builder().sessionId("sess-1").build(), input,
                capturingNext(captured)).blockLast();

        ReasoningInput effective = captured.get();
        assertEquals(2, effective.messages().size());
        assertEquals(MsgRole.USER, effective.messages().get(1).getRole());
        assertTrue(effective.messages().get(1).getTextContent().contains("追加要求"));
    }
}
