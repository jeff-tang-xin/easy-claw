package com.xinl.easyclaw.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy 事件协议的可执行规格。
 *
 * <p>本类不是普通的「跑通就行」测试，而是把已上线前端所依赖的报文形状写成断言：
 * 信封键顺序、事件字段名、NON_NULL 字段的缺省行为、子 Agent content 的分隔符编码。
 * 断言的是精确 JSON 字符串而非 contains——协议回归的典型表现是「多了/少了一个字段」
 * 或「字段改了名」，宽松匹配对这两种情况完全不敏感。
 *
 * <p>后续引入 v2 协议时，本类即等价性判据：要么 v2 通过同一组断言，要么在此显式记录差异。
 */
class LegacyEventSerializerTest {

    private static final String WS = "ws-1";
    private static final String SID = "sess-1";

    private final EventSerializer serializer = new LegacyEventSerializer(new ObjectMapper());

    private String ser(StreamEvent evt) throws Exception {
        return serializer.serialize(WS, SID, evt);
    }

    /** 信封前缀：键顺序 workspaceId → sessionId → event，所有断言共用 */
    private static String envelope(String eventBody) {
        return "{\"workspaceId\":\"ws-1\",\"sessionId\":\"sess-1\",\"event\":" + eventBody + "}";
    }

    @Test
    @DisplayName("信封形状：键顺序 workspaceId → sessionId → event")
    void envelopeKeyOrder() throws Exception {
        assertEquals(envelope("{\"type\":\"text\",\"content\":\"hi\"}"), ser(StreamEvent.text("hi")));
    }

    @Test
    @DisplayName("纯文本类：text / reasoning 只带 type + content")
    void plainTextEvents() throws Exception {
        assertEquals(envelope("{\"type\":\"text\",\"content\":\"你好\"}"), ser(StreamEvent.text("你好")));
        assertEquals(envelope("{\"type\":\"reasoning\",\"content\":\"想一想\"}"),
                ser(StreamEvent.reasoning("想一想")));
    }

    @Test
    @DisplayName("工具类：tool / tool_args / tool_result / tool_end 带 toolCallId，键序 type→content→toolCallId")
    void toolEventsCarryToolCallId() throws Exception {
        assertEquals(envelope("{\"type\":\"tool\",\"content\":\"read_file\",\"toolCallId\":\"call_1\"}"),
                ser(StreamEvent.tool("read_file", "call_1")));
        assertEquals(envelope("{\"type\":\"tool_args\",\"content\":\"{\\\"path\\\":\\\"a.txt\\\"}\",\"toolCallId\":\"call_1\"}"),
                ser(StreamEvent.toolArgs("{\"path\":\"a.txt\"}", "call_1")));
        assertEquals(envelope("{\"type\":\"tool_result\",\"content\":\"SUCCESS\\u0001ok\",\"toolCallId\":\"call_1\"}"),
                ser(StreamEvent.toolResult("SUCCESS\u0001ok", "call_1")));
        assertEquals(envelope("{\"type\":\"tool_end\",\"content\":\"read_file\",\"toolCallId\":\"call_1\"}"),
                ser(StreamEvent.toolEnd("read_file", "call_1")));
    }

    @Test
    @DisplayName("NON_NULL 契约：toolCallId 为 null 时字段整体不出现（前端据此退回就近匹配）")
    void nullToolCallIdFieldAbsent() throws Exception {
        String json = ser(StreamEvent.tool("read_file"));

        assertEquals(envelope("{\"type\":\"tool\",\"content\":\"read_file\"}"), json);
        assertFalse(json.contains("toolCallId"), "字段必须缺席，而不是取值 null——前端判的是 undefined");
        assertFalse(json.contains("null"), "报文里不得出现 null 字面量");
    }

    @Test
    @DisplayName("NON_NULL 契约：subId 为 null 时字段整体不出现（前端据此退回按名归并）")
    void nullSubIdFieldAbsent() throws Exception {
        String json = ser(StreamEvent.subagentEnd("code-expert"));

        assertEquals(envelope("{\"type\":\"subagent_end\",\"content\":\"code-expert\"}"), json);
        assertFalse(json.contains("subId"), "历史转录回放依赖 subId 缺席时的降级路径");
    }

    @Test
    @DisplayName("子 Agent 类：带 subId，键序 type→content→subId（toolCallId 缺席）")
    void subagentEventsCarrySubId() throws Exception {
        assertEquals(envelope("{\"type\":\"subagent_text\",\"content\":\"code-expert\\u0001片段\",\"subId\":\"agent:code-expert:u1\"}"),
                ser(StreamEvent.subagentText("code-expert", "片段", "agent:code-expert:u1")));
        assertEquals(envelope("{\"type\":\"subagent_end\",\"content\":\"code-expert\",\"subId\":\"agent:code-expert:u1\"}"),
                ser(StreamEvent.subagentEnd("code-expert", "agent:code-expert:u1")));
    }

    @Test
    @DisplayName("分隔符契约：agentName 与 delta 之间的 \\u0001 必须序列化为 \\u0001 转义序列")
    void subagentSeparatorEscaping() throws Exception {
        String json = ser(StreamEvent.subagentText("code-expert", "片段", "sub-1"));

        assertTrue(json.contains("\\u0001"), "分隔符必须以 \\u0001 转义形式出现，前端按此切分 content");
        assertFalse(json.contains("\u0001"), "不得输出裸控制字符（合法 JSON 要求转义 U+0001）");
        // 三段编码（agentName \u0001 state \u0001 result）同样逐字保留
        assertEquals(envelope("{\"type\":\"subagent_tool_result\","
                        + "\"content\":\"code-expert\\u0001SUCCESS\\u0001done\",\"subId\":\"sub-1\"}"),
                ser(StreamEvent.subagentToolResult("code-expert", "SUCCESS", "done", "sub-1")));
    }

    @Test
    @DisplayName("控制类：end / stopped 的 content 为空串而非缺席（前端按 type 路由，字段仍须在）")
    void controlEventsKeepEmptyContent() throws Exception {
        assertEquals(envelope("{\"type\":\"end\",\"content\":\"\"}"), ser(StreamEvent.end()));
        assertEquals(envelope("{\"type\":\"stopped\",\"content\":\"\"}"), ser(StreamEvent.stopped()));
        assertEquals(envelope("{\"type\":\"auto_confirm\",\"content\":\"\"}"), ser(StreamEvent.autoConfirm()));
    }

    @Test
    @DisplayName("控制类：error / status / pending_info 形状")
    void errorStatusPendingShapes() throws Exception {
        assertEquals(envelope("{\"type\":\"error\",\"content\":\"炸了\"}"), ser(StreamEvent.error("炸了")));
        assertEquals(envelope("{\"type\":\"status\",\"content\":\"{\\\"running\\\":true,\\\"pending\\\":false}\"}"),
                ser(StreamEvent.status("{\"running\":true,\"pending\":false}")));
        assertEquals(envelope("{\"type\":\"pending_info\",\"content\":\"{\\\"pending\\\":false,\\\"tools\\\":[]}\"}"),
                ser(StreamEvent.pendingInfo("{\"pending\":false,\"tools\":[]}")));
    }

    @Test
    @DisplayName("内含 JSON 载荷类：context / blackboard 的 content 是被转义的 JSON 字符串，不是嵌套对象")
    void jsonPayloadEventsStayStringEncoded() throws Exception {
        assertEquals(envelope("{\"type\":\"context\",\"content\":\"{\\\"messages\\\":12,\\\"tokens\\\":345}\"}"),
                ser(StreamEvent.context("{\"messages\":12,\"tokens\":345}")));
        assertEquals(envelope("{\"type\":\"blackboard\",\"content\":\"{\\\"seq\\\":1,\\\"type\\\":\\\"finding\\\"}\"}"),
                ser(StreamEvent.blackboard("{\"seq\":1,\"type\":\"finding\"}")));
    }

    @Test
    @DisplayName("workspaceId 为空串时仍写入信封（sendJson 的空值告警依赖它，不得省略该键）")
    void emptyWorkspaceIdStillPresent() throws Exception {
        assertEquals("{\"workspaceId\":\"\",\"sessionId\":\"sess-1\",\"event\":{\"type\":\"end\",\"content\":\"\"}}",
                serializer.serialize("", SID, StreamEvent.end()));
    }
}
