package com.xinl.easyclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AgentService.ToolTrace} 内部状态突变的回归测试。
 *
 * <p>为什么要单独锁这几个 boolean：{@code startStream} 在 AGENT_END 时有一条 NO_REPLY 兜底分支
 * （{@code mainTurn && trace.toolCalled && !trace.hasOutput && !retried[0]} → 自动续问），
 * 该分支**不发 end 事件**。所以 {@code hasOutput} 一旦被误判为 false，前端就永久停留在
 * 「正在输出」。特别是「模型把整段正文写进 thinking 通道」的回合 —— UI 其实已有内容，
 * 绝不能算「无回复」。这些语义边界只体现在字段读写上，没有对外可观测的输出，
 * 故只能用反射直接断言字段值。
 *
 * <p>脚手架（反射取 private {@code handleEvent}、反射构造 {@code ToolTrace} / {@code DeltaBatcher}）
 * 沿用同目录 {@link ConcurrentToolEventPairingTest} 的写法，包括其中关于 mock 协作者的说明。
 */
class ToolTraceStateTest {

    private AgentService service;
    private Method handleEvent;
    private Class<?> traceClass;
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

        traceClass = Class.forName("com.xinl.easyclaw.agent.AgentService$ToolTrace");
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

    /** 喂一个事件给 handleEvent；agent 传 null 是安全的（只要工具名不是 agent_spawn/agent_send）。 */
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

    private boolean flag(String name) throws Exception {
        Field f = traceClass.getDeclaredField(name);
        f.setAccessible(true);
        return (boolean) f.get(trace);
    }

    private String reply() throws Exception {
        Field f = traceClass.getDeclaredField("reply");
        f.setAccessible(true);
        return f.get(trace).toString();
    }

    private void appendReply(String delta) throws Exception {
        Method m = traceClass.getDeclaredMethod("appendReply", String.class);
        m.setAccessible(true);
        m.invoke(trace, delta);
    }

    @Test
    @DisplayName("初始 trace 三个标记全 false；TOOL_CALL_START 只置 toolCalled")
    void freshTraceIsCleanAndToolStartOnlyMarksToolCalled() throws Exception {
        assertFalse(flag("toolCalled"), "新建 trace 不应认为已调用工具");
        assertFalse(flag("hasText"), "新建 trace 不应认为已有正文");
        assertFalse(flag("hasOutput"), "新建 trace 不应认为已有产出");
        assertEquals("", reply(), "新建 trace 的 reply 应为空");

        List<StreamEvent> out = new ArrayList<>();
        feed(new ToolCallStartEvent("reply-1", "call_A", "read_file"), out);

        assertTrue(flag("toolCalled"), "TOOL_CALL_START 必须置 toolCalled");
        // 工具调用本身不是面向用户的「回复」：hasOutput 仍为 false 才能让 NO_REPLY 兜底有机会触发
        assertFalse(flag("hasText"), "工具调用不产生正文");
        assertFalse(flag("hasOutput"), "工具调用不算本回合产出，否则 NO_REPLY 兜底永不触发");
        assertEquals("", reply(), "工具调用不应写入 reply");
    }

    @Test
    @DisplayName("纯文本 delta：hasText / hasOutput 同时置位，reply 按序累积")
    void textDeltaMarksBothFlagsAndAccumulatesReply() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        feed(new TextBlockDeltaEvent("reply-1", "blk-1", "你好"), out);
        feed(new TextBlockDeltaEvent("reply-1", "blk-1", "，世界"), out);

        assertTrue(flag("hasText"), "TEXT_BLOCK_DELTA 必须置 hasText");
        assertTrue(flag("hasOutput"), "TEXT_BLOCK_DELTA 必须置 hasOutput");
        assertFalse(flag("toolCalled"), "纯文本回合不应标记 toolCalled");
        assertEquals("你好，世界", reply(), "reply 应等于各 delta 的顺序拼接");
    }

    @Test
    @DisplayName("纯 thinking delta：只置 hasOutput，不置 hasText、不进 reply（守住 thinking-only 回合不被误判 NO_REPLY）")
    void thinkingOnlyTurnCountsAsOutputButNotAsText() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        feed(new ToolCallStartEvent("reply-1", "call_A", "read_file"), out);
        feed(new ThinkingBlockDeltaEvent("reply-1", "blk-1", "先看一下文件"), out);
        feed(new ThinkingBlockDeltaEvent("reply-1", "blk-1", "，再决定怎么改"), out);

        // 关键断言：thinking 通道有内容 → UI 已有展示 → 不能进 NO_REPLY 自动续问分支
        assertTrue(flag("hasOutput"), "THINKING_BLOCK_DELTA 必须置 hasOutput，否则 NO_REPLY 兜底会吞掉 end 事件");
        assertFalse(flag("hasText"), "THINKING_BLOCK_DELTA 不应置 hasText（正文通道确实为空）");
        assertEquals("", reply(), "thinking 内容不入 reply（reply 只用于编排审计标记比对）");
        assertTrue(flag("toolCalled"), "toolCalled 由 TOOL_CALL_START 置位，不受 thinking 影响");
    }

    @Test
    @DisplayName("appendReply 超 8192 上限时滑动保留尾部（审计标记在尾部，不能截尾）")
    void appendReplySlidesWindowKeepingTail() throws Exception {
        String head = "HEAD-MARKER";
        String tail = "<orchestration-audit>done</orchestration-audit>";
        appendReply(head);
        appendReply("x".repeat(8192));
        appendReply(tail);

        String reply = reply();
        assertEquals(8192, reply.length(), "reply 长度必须被限制在 8192");
        assertTrue(reply.endsWith(tail), "必须保留尾部：审计标记出现在回复末尾");
        assertFalse(reply.contains(head), "超限后头部内容应已被滑出窗口");

        // null / 空 delta 不改变已累积内容，也不触发越界
        appendReply(null);
        appendReply("");
        assertEquals(reply, reply(), "null/空 delta 应被忽略");
    }

    @Test
    @DisplayName("子 Agent 事件（source 含 '/'）不污染主回合的 trace 标记")
    void subagentEventsDoNotTouchMainTrace() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        feed(new TextBlockDeltaEvent("reply-1", "blk-1", "子").withSource("main/coder"), out);
        feed(new ThinkingBlockDeltaEvent("reply-1", "blk-1", "思").withSource("main/coder"), out);
        feed(new ToolCallStartEvent("reply-1", "call_A", "read_file").withSource("main/coder"), out);

        assertFalse(flag("hasText"), "子 Agent 正文不算主回合正文");
        assertFalse(flag("hasOutput"), "子 Agent 产出不算主回合产出");
        assertFalse(flag("toolCalled"), "子 Agent 的工具调用不算主回合调用");
        assertEquals("", reply(), "子 Agent 文本不入主回合 reply");
    }
}
