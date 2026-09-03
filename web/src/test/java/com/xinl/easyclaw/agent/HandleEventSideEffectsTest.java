package com.xinl.easyclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AgentService.handleEvent} 的副作用回归测试（HITL 确认分支）。
 *
 * <p>{@code handleEvent} 既做「框架事件 → 前端 StreamEvent」的翻译，也顺带触发若干外部副作用。
 * 其中最关键的一类是 HITL：{@code REQUIRE_USER_CONFIRM} 必须把待确认工具登记进
 * {@link SessionRegistry}，否则用户点「允许」时 {@code confirmTools} 查不到 pending，
 * 弹窗按钮点了没反应、回合永久挂起（既有护栏 {@code sweepExpiredConfirmations} 也依赖
 * 同时写入的截止时间）。
 *
 * <p>把副作用单独锁在这里，是为了后续把它们从翻译逻辑中剥离时有兜底：
 * 同目录 {@code ConcurrentToolEventPairingTest} 只覆盖翻译输出，改动副作用位置时它不会变红。
 *
 * <p>脚手架沿用 {@code ConcurrentToolEventPairingTest}：反射驱动 private {@code handleEvent}
 * 与包私有内部类 {@code ToolTrace}/{@code DeltaBatcher}；{@code SessionRegistry} 无依赖，
 * 故用真实实例并直接断言其公开查询方法，不 mock。
 */
class HandleEventSideEffectsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 真实实例（非 mock）：断言的对象就是它 */
    private SessionRegistry sessions;
    private AgentScopeProperties properties;
    private AgentService service;
    private Method handleEvent;
    private Object trace;

    @BeforeEach
    void setUp() throws Exception {
        sessions = new SessionRegistry();
        properties = new AgentScopeProperties();
        service = new AgentService(
                mock(WorkspaceManager.class),
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                properties,
                sessions,
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

    private static RequireUserConfirmEvent confirmEvent() {
        return new RequireUserConfirmEvent("reply-1", List.of(
                ToolUseBlock.builder()
                        .id("call_A")
                        .name("write_file")
                        .input(Map.of("path", "a.txt", "content", "hello"))
                        .build(),
                ToolUseBlock.builder()
                        .id("call_B")
                        .name("execute_shell_command")
                        .input(Map.of("command", "ls"))
                        .build()));
    }

    @Test
    @DisplayName("REQUIRE_USER_CONFIRM 必须把待确认工具登记进 SessionRegistry（否则确认按钮点了无效）")
    void requireUserConfirmMustArmPendingConfirm() throws Exception {
        List<StreamEvent> out = new ArrayList<>();
        assertFalse(sessions.hasPendingConfirm("session-1"), "前置条件：事件前不应有待确认工具");

        feed(confirmEvent(), out);

        assertTrue(sessions.hasPendingConfirm("session-1"), "待确认工具未登记，确认流程会永久挂起");
        List<ToolUseBlock> pending = sessions.peekPendingConfirm("session-1");
        assertNotNull(pending, "peekPendingConfirm 返回 null");
        assertEquals(List.of("write_file", "execute_shell_command"),
                pending.stream().map(ToolUseBlock::getName).toList(),
                "登记的工具名/顺序与事件不一致");
        assertEquals(List.of("call_A", "call_B"),
                pending.stream().map(ToolUseBlock::getId).toList(),
                "登记的工具调用 id 与事件不一致");

        // 只登记本会话，不误伤其他会话
        assertFalse(sessions.hasPendingConfirm("session-other"));
    }

    @Test
    @DisplayName("登记待确认工具时必须同时按配置写入确认截止时间（超时兜底的唯一依据）")
    void requireUserConfirmMustArmConfirmDeadline() throws Exception {
        int timeoutMinutes = properties.getAgent().getConfirmTimeoutMinutes();
        assertTrue(timeoutMinutes > 0, "默认配置应为正值，否则本用例前提不成立");

        feed(confirmEvent(), new ArrayList<>());

        long beforeDeadline = System.currentTimeMillis() + timeoutMinutes * 60_000L - 1000L;
        assertFalse(sessions.expiredConfirmations(beforeDeadline).contains("session-1"),
                "截止时间不应早于配置的超时时长");
        long afterDeadline = System.currentTimeMillis() + timeoutMinutes * 60_000L + 1000L;
        assertTrue(sessions.expiredConfirmations(afterDeadline).contains("session-1"),
                "未写入确认截止时间，用户不响应时会话状态与 SSE 连接会永久常驻");
    }

    @Test
    @DisplayName("REQUIRE_USER_CONFIRM 同时下发 type=confirm 事件，content 为含 replyId 与 tools 的合法 JSON")
    void requireUserConfirmMustEmitConfirmStreamEvent() throws Exception {
        List<StreamEvent> out = new ArrayList<>();

        feed(confirmEvent(), out);

        List<StreamEvent> confirms = out.stream().filter(e -> "confirm".equals(e.type())).toList();
        assertEquals(1, confirms.size(), "期望恰好 1 个 confirm 事件，实际全部事件=" + out);

        JsonNode root = MAPPER.readTree(confirms.get(0).content());
        assertEquals("reply-1", root.path("replyId").asText(), "confirm JSON 的 replyId 不正确");

        JsonNode tools = root.path("tools");
        assertTrue(tools.isArray(), "tools 必须是数组，实际=" + tools);
        assertEquals(2, tools.size(), "tools 数量不正确");

        assertEquals("call_A", tools.get(0).path("id").asText());
        assertEquals("write_file", tools.get(0).path("name").asText());
        assertEquals("a.txt", tools.get(0).path("input").path("path").asText(),
                "工具入参必须原样透传给前端弹窗展示");

        assertEquals("call_B", tools.get(1).path("id").asText());
        assertEquals("execute_shell_command", tools.get(1).path("name").asText());
        assertEquals("ls", tools.get(1).path("input").path("command").asText());
    }
}
