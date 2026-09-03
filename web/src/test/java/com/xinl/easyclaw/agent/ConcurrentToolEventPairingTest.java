package com.xinl.easyclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发工具调用的事件配对回归测试。
 *
 * <p>线上现象：先发起的 {@code read_file} 卡片永久停留在「执行中」，而后发起的工具已显示完成。
 * 根因是 {@code ToolTrace} 早期为「单槽」结构 —— 第二次 TOOL_CALL_START 直接覆盖第一次的
 * 名称/入参/结果，于是第一次调用的 TOOL_RESULT_END 会带着第二个工具的名字发出去，
 * 第一张卡片永远等不到属于它的 tool_end。
 *
 * <p>这里通过反射直接驱动 {@code handleEvent}（private，且真实链路需要完整 Agent 装配），
 * 断言交错事件流下每次调用的 tool_result / tool_end 都带回自己的 toolCallId 与工具名。
 *
 * <p>按项目惯例本应手写 fake，但 AgentService 构造依赖 6 个协作者且本用例完全不触碰它们，
 * 故沿用同目录 StuckToolRecoveryTest 已认可的 mock 破例。
 */
class ConcurrentToolEventPairingTest {

    private AgentService service;
    private Method handleEvent;
    private Object trace;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentService(
                mock(WorkspaceManager.class),
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                new AgentScopeProperties(),
                new SessionRegistry(),
                mock(ScenarioResolver.class),
                new com.xinl.easyclaw.workspace.WorkspaceFileLayout());

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
        }, trace, "session-1", null, batcher);
    }

    @Test
    @DisplayName("两次工具调用交错返回时，tool_result/tool_end 必须各自带回正确的 id 与工具名")
    void interleavedCallsMustNotStealEachOthersCards() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        String r = "reply-1";

        // 两次调用先后开始（尚未有任何结果）—— 这是单槽实现被覆盖的时刻
        feed(new ToolCallStartEvent(r, "call_A", "read_file"), out);
        feed(new ToolCallDeltaEvent(r, "call_A", "read_file", "{\"path\":\"a.txt\"}"), out);
        feed(new ToolCallStartEvent(r, "call_B", "grep_files"), out);
        feed(new ToolCallDeltaEvent(r, "call_B", "grep_files", "{\"pattern\":\"x\"}"), out);
        feed(new ToolCallEndEvent(r, "call_B", "grep_files"), out);
        feed(new ToolCallEndEvent(r, "call_A", "read_file"), out);

        // 后发起的 B 先返回，随后 A 才返回
        feed(new ToolResultStartEvent(r, "call_B", "grep_files"), out);
        feed(new ToolResultTextDeltaEvent(r, "call_B", "grep_files", "3 matches"), out);
        feed(new ToolResultEndEvent(r, "call_B", "grep_files", ToolResultState.SUCCESS), out);

        feed(new ToolResultStartEvent(r, "call_A", "read_file"), out);
        feed(new ToolResultTextDeltaEvent(r, "call_A", "read_file", "file body"), out);
        feed(new ToolResultEndEvent(r, "call_A", "read_file", ToolResultState.SUCCESS), out);

        // 每次调用的入参必须回到自己的卡片，不能串台
        assertEquals("{\"path\":\"a.txt\"}", findOne(out, "tool_args", "call_A").content());
        assertEquals("{\"pattern\":\"x\"}", findOne(out, "tool_args", "call_B").content());

        // 结果必须各归其主
        assertTrue(findOne(out, "tool_result", "call_A").content().contains("file body"));
        assertTrue(findOne(out, "tool_result", "call_B").content().contains("3 matches"));

        // 关键断言：两张卡片都要收到带自己 id 的 tool_end，且工具名正确
        assertEquals("read_file", findOne(out, "tool_end", "call_A").content());
        assertEquals("grep_files", findOne(out, "tool_end", "call_B").content());
    }

    @Test
    @DisplayName("流中途终止时，所有在途工具都要补发收尾，而不只是最后一个")
    void abortMustCloseEveryInFlightCall() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        String r = "reply-2";

        feed(new ToolCallStartEvent(r, "call_A", "read_file"), out);
        feed(new ToolCallStartEvent(r, "call_B", "grep_files"), out);
        // 两个都在途，此时流被终止

        Method close = AgentService.class.getDeclaredMethod(
                "closeInFlightTool", trace.getClass(), Consumer.class, String.class);
        close.setAccessible(true);
        List<StreamEvent> closing = new ArrayList<>();
        close.invoke(service, trace, (Consumer<StreamEvent>) closing::add, "已中断");

        assertEquals("read_file", findOne(closing, "tool_end", "call_A").content());
        assertEquals("grep_files", findOne(closing, "tool_end", "call_B").content());

        // 幂等：再次收尾不应重复补发
        List<StreamEvent> again = new ArrayList<>();
        close.invoke(service, trace, (Consumer<StreamEvent>) again::add, "已中断");
        assertTrue(again.isEmpty(), "重复收尾不应再发事件: " + again);
    }

    private StreamEvent findOne(List<StreamEvent> events, String type, String callId) {
        List<StreamEvent> hits = events.stream()
                .filter(e -> type.equals(e.type()) && callId.equals(e.toolCallId()))
                .toList();
        assertEquals(1, hits.size(),
                "期望恰好 1 个 " + type + "(" + callId + ")，实际 " + hits.size() + "，全部事件=" + events);
        return hits.get(0);
    }
}
