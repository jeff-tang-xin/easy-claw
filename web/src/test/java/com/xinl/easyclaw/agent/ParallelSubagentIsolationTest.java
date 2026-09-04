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
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并行同名子 Agent 的隔离回归测试。
 *
 * <p>背景：{@code AgentEvent.getSource()} 只编码 {@code parentSession/agentId}，同一角色被并行
 * 派发两次时两个实例的 source 完全相同（框架 {@code AgentEvent} 的 Javadoc 明确警告过这点）。
 * 早先 {@code DeltaBatcher} 直接用角色名当累积桶的 key，于是两个 code-expert 的正文增量
 * 会拼进同一个 StringBuilder —— 前端看到的是两路输出交错的一段乱码文本。
 *
 * <p>本测试锁住修复后的两条不变式：
 * <ul>
 *   <li>桶按 {@code metadata.agentInstanceId} 分，不同实例的 delta 不互相污染；</li>
 *   <li>外发事件的展示名仍是角色名（不能因为改了桶键就把 UUID 显示给用户），
 *       实例身份走独立的 {@code subId} 字段。</li>
 * </ul>
 *
 * <p>脚手架沿用 {@code HandleEventSideEffectsTest}：反射驱动 private {@code handleSubagentEvent}。
 * 这里刻意共用同一个 {@code DeltaBatcher}，因为串桶恰恰发生在共享 batcher 内部；每个测试各建
 * batcher 反而会让 bug 复现不出来。
 */
class ParallelSubagentIsolationTest {

    /** 与 AgentSpawnTool 打标时使用的 metadata 键一致 */
    private static final String INSTANCE_ID = AgentEvent.METADATA_AGENT_INSTANCE_ID;

    private AgentService service;
    private Method handleSubagentEvent;
    private Object batcher;
    private List<StreamEvent> out;

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

        for (Method m : AgentService.class.getDeclaredMethods()) {
            if (m.getName().equals("handleSubagentEvent")) {
                m.setAccessible(true);
                handleSubagentEvent = m;
                break;
            }
        }
        assertNotNull(handleSubagentEvent, "handleSubagentEvent 未找到，测试需同步更新");

        out = new ArrayList<>();
        Consumer<StreamEvent> sink = out::add;
        Class<?> bc = Class.forName("com.xinl.easyclaw.agent.AgentService$DeltaBatcher");
        Constructor<?> c = bc.getDeclaredConstructor(Consumer.class);
        c.setAccessible(true);
        batcher = c.newInstance(sink);
    }

    /**
     * 让 batcher 脱离 idle：塞入一条主文本 delta 并把 lastEmitAt 顶到「刚刚」，
     * 使后续子 Agent delta 落入累积桶而非直发。
     */
    private void primeBuffer() throws Exception {
        java.lang.reflect.Field lastEmit = batcher.getClass().getDeclaredField("lastEmitAt");
        lastEmit.setAccessible(true);
        lastEmit.set(batcher, System.currentTimeMillis());
        java.lang.reflect.Field textBuf = batcher.getClass().getDeclaredField("textBuf");
        textBuf.setAccessible(true);
        ((StringBuilder) textBuf.get(batcher)).append("x");
    }

    /**
     * 喂一条带实例标识的子 Agent 正文增量（走完整事件路径）。
     */
    private void feedText(String roleName, String instanceId, String delta) throws Exception {
        AgentEvent event = new TextBlockDeltaEvent("reply-1", "block-1", delta)
                .withSource("session-1/" + roleName)
                .withMetadataEntry(INSTANCE_ID, instanceId);
        Consumer<StreamEvent> sink = out::add;
        handleSubagentEvent.invoke(service, event, roleName, sink, batcher, "session-1");
    }

    /** 取某个 subId 对应的全部正文，按到达顺序拼接 */
    private String textOf(String subId) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent e : out) {
            if (!"subagent_text".equals(e.type())) {
                continue;
            }
            if (subId.equals(e.subId())) {
                String c = e.content();
                int sep = c.indexOf('\u0001');
                sb.append(sep >= 0 ? c.substring(sep + 1) : c);
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("两个同名子 Agent 交错直发时，各自 delta 携带正确 subId 不混淆")
    void parallelSameNameSubagentsCarryDistinctSubId() throws Exception {
        // delta 稀疏时 batcher 直接透传（不入桶），此路径的实例隔离完全依赖 subId 字段
        primeBuffer();
        feedText("code-expert", "inst-A", "AAA");
        feedText("code-expert", "inst-B", "BBB");
        feedText("code-expert", "inst-A", "aaa");
        feedText("code-expert", "inst-B", "bbb");
        flush();

        StringBuilder dump = new StringBuilder();
        for (StreamEvent e : out) {
            dump.append('[').append(e.type()).append('|')
                    .append(String.valueOf(e.content()).replace("\u0001", "<US>"))
                    .append('|').append(e.subId()).append(']');
        }
        assertEquals("AAAaaa", textOf("inst-A"), "实例 A 的正文被污染；实际事件=" + dump);
        assertEquals("BBBbbb", textOf("inst-B"), "实例 B 的正文被污染；实际事件=" + dump);
    }

    @Test
    @DisplayName("展示名仍是角色名，实例 id 只走 subId 字段不外泄到标签")
    void displayNameStaysRoleNameNotInstanceKey() throws Exception {
        feedText("code-expert", "inst-A", "hello");
        flush();

        List<StreamEvent> texts = out.stream().filter(e -> "subagent_text".equals(e.type())).toList();
        assertTrue(!texts.isEmpty(), "未产生 subagent_text 事件");
        for (StreamEvent e : texts) {
            String c = e.content();
            int sep = c.indexOf('\u0001');
            String shown = sep >= 0 ? c.substring(0, sep) : "";
            assertEquals("code-expert", shown, "展示名不应是实例键");
            assertEquals("inst-A", e.subId(), "实例身份应通过 subId 传递");
        }
    }

    @Test
    @DisplayName("缺少 agentInstanceId 时降级为按 source 分桶，subId 留空")
    void missingInstanceIdFallsBackToSource() throws Exception {
        AgentEvent event = new TextBlockDeltaEvent("reply-1", "block-1", "legacy")
                .withSource("session-1/code-expert");
        Consumer<StreamEvent> sink = out::add;
        handleSubagentEvent.invoke(service, event, "code-expert", sink, batcher, "session-1");
        flush();

        List<StreamEvent> texts = out.stream().filter(e -> "subagent_text".equals(e.type())).toList();
        assertEquals(1, texts.size(), "应正常产出一条正文事件");
        assertEquals(null, texts.get(0).subId(),
                "拿不到实例身份时 subId 必须留空，让前端退化为按名归并");
    }

    private void flush() throws Exception {
        Method m = batcher.getClass().getDeclaredMethod("flush");
        m.setAccessible(true);
        m.invoke(batcher);
    }
}
