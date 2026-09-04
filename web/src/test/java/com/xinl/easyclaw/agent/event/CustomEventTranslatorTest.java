package com.xinl.easyclaw.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.middleware.FileChangeMiddleware;
import com.xinl.easyclaw.middleware.ToolFailGuard;
import io.agentscope.core.event.CustomEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomEventTranslator} 的协议形状回归测试。
 *
 * <p>本类守的是「前端能否认出这些事件」——翻译结果的 {@code type} 与 JSON 字段名都是
 * 已上线前端的路由依据，改动会导致前端静默丢弃提示（不报错、只是功能消失），
 * 属于最难靠人工发现的一类回归，故逐字段断言而非只断言不抛异常。
 */
class CustomEventTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CustomEventTranslator translator = new CustomEventTranslator(mapper);

    private List<StreamEvent> collect(CustomEvent event) {
        List<StreamEvent> out = new ArrayList<>();
        translator.translate(event, out::add);
        return out;
    }

    @Test
    @DisplayName("file_changed：翻译为 file_changed 事件，content 为路径")
    void fileChangedCarriesPath() {
        List<StreamEvent> out = collect(
                new CustomEvent(FileChangeMiddleware.EVENT_NAME, Map.of("path", "src/main/App.java")));

        assertEquals(1, out.size(), "应恰好产出一个事件");
        assertEquals("file_changed", out.get(0).type());
        assertEquals("src/main/App.java", out.get(0).content());
    }

    @Test
    @DisplayName("file_changed：path 为空串仍须推送（shell 类工具「有变更但位置未知」是有效载荷）")
    void fileChangedKeepsEmptyPath() {
        List<StreamEvent> out = collect(
                new CustomEvent(FileChangeMiddleware.EVENT_NAME, Map.of("path", "")));

        assertEquals(1, out.size(), "空串不是无效值，不能被过滤掉");
        assertEquals("file_changed", out.get(0).type());
        assertEquals("", out.get(0).content());
    }

    @Test
    @DisplayName("file_changed：path 缺失时降级为空串而非 \"null\" 字面量")
    void fileChangedMissingPathBecomesEmpty() {
        List<StreamEvent> out = collect(
                new CustomEvent(FileChangeMiddleware.EVENT_NAME, new HashMap<>()));

        assertEquals(1, out.size());
        assertEquals("", out.get(0).content(), "不得把 null 拼成字符串 \"null\" 推给前端");
    }

    @Test
    @DisplayName("tool_fail_guard：翻译为 context 事件，JSON 形状与旧实现一致")
    void toolFailGuardShape() throws Exception {
        Map<String, Object> value = new HashMap<>();
        value.put("tool", "execute");
        value.put("fails", 3);
        value.put("message", "连续失败已达上限");

        List<StreamEvent> out = collect(new CustomEvent(ToolFailGuard.EVENT_NAME, value));

        assertEquals(1, out.size());
        // 前端按 type=context 收，再按 payload.type 二次路由，两层都不能变
        assertEquals("context", out.get(0).type());

        JsonNode payload = mapper.readTree(out.get(0).content());
        assertEquals("tool_fail_guard", payload.get("type").asText());
        assertEquals("execute", payload.get("tool").asText());
        assertEquals(3, payload.get("fails").asInt());
        assertEquals("连续失败已达上限", payload.get("message").asText());
    }

    @Test
    @DisplayName("tool_fail_guard：fails 非数字时降级为 0，不抛异常")
    void toolFailGuardNonNumericFails() throws Exception {
        Map<String, Object> value = new HashMap<>();
        value.put("tool", "execute");
        value.put("fails", "not-a-number");
        value.put("message", "x");

        List<StreamEvent> out = collect(new CustomEvent(ToolFailGuard.EVENT_NAME, value));

        assertEquals(1, out.size());
        JsonNode payload = mapper.readTree(out.get(0).content());
        assertEquals(0, payload.get("fails").asInt(), "非数字应降级为 0 而非中断翻译");
    }

    @Test
    @DisplayName("未知 name 静默跳过：框架 CustomEvent 协议演进要求，不得抛异常")
    void unknownNameSilentlySkipped() {
        List<StreamEvent> out = collect(new CustomEvent("some_future_event", Map.of("k", "v")));

        assertTrue(out.isEmpty(), "未知事件必须被静默丢弃，否则新版 middleware 会打挂旧消费端");
    }

    @Test
    @DisplayName("null 事件与 null value 均不抛异常")
    void nullInputsAreSafe() {
        assertTrue(collect(null).isEmpty(), "null 事件应安全跳过");

        // value 为 null 的 file_changed：不得 NPE，降级为空路径
        List<StreamEvent> out = collect(new CustomEvent(FileChangeMiddleware.EVENT_NAME, null));
        assertEquals(1, out.size());
        assertEquals("", out.get(0).content());
    }
}
