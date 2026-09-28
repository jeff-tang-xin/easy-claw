package com.xinl.easyclaw.agent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamEvent} 的 toolCallId 契约测试。
 *
 * <p>背景：并发工具调用时，前端按「最后一个工具段」或按工具名匹配 tool_result / tool_end，
 * 会把先返回的结果贴到后发起的卡片上，导致先发起的工具卡片永久停留在「执行中」。
 * 修复方式是让工具类事件透传框架的 toolCallId，由前端精确配对。
 *
 * <p>这些断言锁定两件事：
 * <ol>
 *   <li>工具类事件确实带上了 id，且序列化字段名与前端 {@code StreamEvent.toolCallId} 一致；</li>
 *   <li>非工具事件不会多出 null 字段（避免历史转录 JSON 体积与结构变化）。</li>
 * </ol>
 */
class StreamEventToolCallIdTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("工具类事件应携带 toolCallId")
    void toolEventsCarryCallId() {
        assertEquals("call_1", StreamEvent.tool("read_file", "call_1").toolCallId());
        assertEquals("call_2", StreamEvent.toolArgs("{}", "call_2").toolCallId());
        assertEquals("call_3", StreamEvent.toolResult("(OK) done", "call_3").toolCallId());
        assertEquals("call_4", StreamEvent.toolEnd("read_file", "call_4").toolCallId());
    }

    @Test
    @DisplayName("两参工厂保持向后兼容：toolCallId 为 null，不破坏历史调用方")
    void legacyFactoriesKeepNullId() {
        assertNull(StreamEvent.tool("read_file").toolCallId());
        assertNull(StreamEvent.toolEnd("read_file").toolCallId());
        assertNull(new StreamEvent("text", "hello").toolCallId());
        assertEquals("text", new StreamEvent("text", "hello").type());
        assertEquals("hello", new StreamEvent("text", "hello").content());
    }

    @Test
    @DisplayName("序列化字段名为 toolCallId，且为 null 时整个字段省略")
    void serializationContract() throws Exception {
        String withId = mapper.writeValueAsString(StreamEvent.tool("read_file", "call_1"));
        assertTrue(withId.contains("\"toolCallId\":\"call_1\""),
                "前端按 toolCallId 读取，字段名不可更改: " + withId);

        String withoutId = mapper.writeValueAsString(new StreamEvent("text", "hi"));
        assertFalse(withoutId.contains("toolCallId"),
                "非工具事件不应出现 toolCallId 字段: " + withoutId);
    }
}
