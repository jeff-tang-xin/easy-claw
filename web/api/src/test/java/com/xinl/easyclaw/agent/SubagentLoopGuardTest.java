package com.xinl.easyclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceFileLayout;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 子 Agent 循环调度防护（guardSubagentLoop）的回归测试。
 *
 * <p>防护判据不是「派得多」，而是「拿到上一次结果后仍反复重派同一个子 Agent」：
 * team 模式同一轮并发派多个属健康并行，一个结果都还没回，不应计数。
 * 因此副作用链有严格时序——TOOL_CALL_END 触发计数判定，TOOL_RESULT_END 才登记交付记录。
 *
 * <p>本测试是 Phase 3a（把副作用从 handleEvent 剥离）的护栏：剥离后这些断言必须依然成立。
 * 通过反射驱动 private 的 {@code handleEvent}，沿用同目录 ConcurrentToolEventPairingTest 的脚手架。
 */
class SubagentLoopGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "session-loop";

    private AgentService service;
    private Method handleEvent;
    private Object trace;
    private HarnessAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        AgentScopeProperties props = new AgentScopeProperties();
        // 阈值调到 1，用最短事件序列即可跨过告警线（默认 3 需要更多轮派发）
        props.getAgent().setMaxSameSubagentCalls(1);
        agent = mock(HarnessAgent.class);

        service = new AgentService(
                mock(WorkspaceManager.class),
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                props,
                new SessionRegistry(),
                mock(ScenarioResolver.class),
                new WorkspaceFileLayout());

        Class<?> traceClass = Class.forName("com.xinl.easyclaw.agent.AgentService$ToolTrace");
        Constructor<?> ctor = traceClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        trace = ctor.newInstance();

        for (Method m : AgentService.class.getDeclaredMethods()) {
            if (m.getName().equals("handleEvent")) {
                m.setAccessible(true);
                handleEvent = m;
                break;
            }
        }
        assertNotNull(handleEvent, "handleEvent 方法未找到，测试需同步更新");
    }

    private void feed(AgentEvent event, List<StreamEvent> out) throws Exception {
        Consumer<StreamEvent> sink = out::add;
        Class<?> batcherClass = Class.forName("com.xinl.easyclaw.agent.AgentService$DeltaBatcher");
        Constructor<?> bc = batcherClass.getDeclaredConstructor(Consumer.class);
        bc.setAccessible(true);
        Object batcher = bc.newInstance(sink);
        handleEvent.invoke(service, event, sink, (Consumer<Throwable>) t -> {
        }, (Runnable) () -> {
        }, trace, SESSION_ID, sideEffects(sink), batcher);
    }

    /**
     * 构造生产实现 {@code AgentService$SessionSideEffects}，而不是伪造 SideEffectSink。
     * <p>
     * 本测试要验证的是「防护确实会 interrupt agent 并推 loop_warning」这条完整链路，
     * 用假实现只能证明「接口被调到」，无法证明防护真的生效。
     */
    private Object sideEffects(Consumer<StreamEvent> sink) throws Exception {
        Class<?> cls = Class.forName("com.xinl.easyclaw.agent.AgentService$SessionSideEffects");
        Constructor<?> c = cls.getDeclaredConstructors()[0];
        c.setAccessible(true);
        return c.newInstance(service, SESSION_ID, agent, sink);
    }

    /**
     * 一次派发的调用段：START → DELTA(完整参数) → END。
     * args 必须经 DELTA 累积，否则 extractDispatchTarget 取不到 agent_id、身份退化为 "(未知)"。
     */
    private void dispatchCall(String callId, String subagent, String tool, List<StreamEvent> out)
            throws Exception {
        feed(new ToolCallStartEvent("reply-1", callId, tool), out);
        feed(new ToolCallDeltaEvent("reply-1", callId, tool,
                "{\"agent_id\":\"" + subagent + "\",\"task\":\"review\"}"), out);
        feed(new ToolCallEndEvent("reply-1", callId, tool), out);
    }

    /** 结果段：TOOL_RESULT_END 才会写下「该子 Agent 已交付」的记录。 */
    private void dispatchResult(String callId, String tool, List<StreamEvent> out) throws Exception {
        feed(new ToolResultEndEvent("reply-1", callId, tool, ToolResultState.SUCCESS), out);
    }

    /** 从事件流里挑出 loop_warning（它包在 type=context 的 content JSON 内部）。 */
    private List<JsonNode> loopWarnings(List<StreamEvent> out) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (StreamEvent e : out) {
            if (!"context".equals(e.type()) || e.content() == null) {
                continue;
            }
            JsonNode node = MAPPER.readTree(e.content());
            if ("loop_warning".equals(node.path("type").asText())) {
                found.add(node);
            }
        }
        return found;
    }

    @Test
    @DisplayName("并行派发不计数：只有 TOOL_CALL_END、没有交付记录时不得告警")
    void parallelDispatchWithoutDeliveryMustNotWarn() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        // 同一轮里连派 5 次，一个结果都没回 —— 健康并行，不是循环
        for (int i = 0; i < 5; i++) {
            dispatchCall("call_" + i, "reviewer", "agent_spawn", out);
        }

        assertTrue(loopWarnings(out).isEmpty(), "无交付记录的并行派发被误判为循环调度");
        verify(agent, never()).interrupt();
    }

    @Test
    @DisplayName("串行重派触发告警：拿到结果后仍反复重派同一子 Agent → interrupt + loop_warning")
    void serialRedispatchAfterDeliveryMustWarnAndInterrupt() throws Exception {
        List<StreamEvent> out = new ArrayList<>();

        // 每轮必须用新 callId：TOOL_RESULT_END 会把该槽位从 trace.calls 移除
        // 第 1 轮：END 时尚无交付记录 → 不计数；RESULT_END 登记交付
        dispatchCall("call_1", "reviewer", "agent_spawn", out);
        dispatchResult("call_1", "agent_spawn", out);
        // 第 2 轮：END 时已有交付记录 → count=1，不 > max(1)
        dispatchCall("call_2", "reviewer", "agent_spawn", out);
        dispatchResult("call_2", "agent_spawn", out);
        // 第 3 轮：END 时 count=2 > max(1) → 告警
        dispatchCall("call_3", "reviewer", "agent_spawn", out);

        List<JsonNode> warnings = loopWarnings(out);
        assertEquals(1, warnings.size(), "应恰好告警一次");
        JsonNode w = warnings.get(0);
        assertEquals("loop_warning", w.path("type").asText());
        assertEquals("reviewer", w.path("subagent").asText(), "告警应指名具体子 Agent");
        assertEquals(2, w.path("count").asInt(), "count 应为已记录的重派次数");
        assertTrue(w.path("message").asText().contains("reviewer"), "message 应含子 Agent 名");
        verify(agent).interrupt();
    }

    @Test
    @DisplayName("非调度类工具不受防护影响：read_file 反复调用不告警")
    void nonDispatchToolMustNeverWarn() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            dispatchCall("call_r" + i, "reviewer", "read_file", out);
            dispatchResult("call_r" + i, "read_file", out);
        }

        assertTrue(loopWarnings(out).isEmpty(), "普通工具被误纳入子 Agent 循环防护");
        verify(agent, never()).interrupt();
    }
}
