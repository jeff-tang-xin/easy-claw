package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.BoxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * transcript.jsonl 的向前/向后兼容回归。
 * <p>
 * 存量转录已达 90MB 级，{@link SessionTranscriptStore#read} 的坏行是「跳过」而非「报错」，
 * 所以格式不兼容的表现是「历史消息悄悄变少」，没有任何断言兜着就没人会发现。
 * 本测试用<b>代码内嵌的固定旧格式 jsonl 文本</b>驱动读取（不经 TranscriptRecorder 写入、
 * 不依赖外部 fixture），这样即便将来写侧改了实现，这些样本仍代表磁盘上真实的存量数据。
 * <p>
 * 任何一条用例变红都意味着：本次改动会让线上已有会话历史丢消息。
 */
class TranscriptBackwardCompatTest {

    /** 存量磁盘格式（写侧当前输出的字段全集），逐字硬编码，请勿"顺手"改成用对象拼。 */
    private static final String LINE_USER =
            "{\"id\":\"m-1a2b3c4d\",\"type\":\"USER\",\"content\":\"你好\",\"toolName\":\"\",\"toolArgs\":\"\","
                    + "\"toolResult\":\"\",\"subagentName\":\"\",\"images\":[],\"seq\":1,\"timestamp\":1740000000001}";

    private static final String LINE_AI_TEXT =
            "{\"id\":\"m-2b3c4d5e\",\"type\":\"AI_TEXT\",\"content\":\"世界\",\"toolName\":\"\",\"toolArgs\":\"\","
                    + "\"toolResult\":\"\",\"subagentName\":\"\",\"images\":[],\"seq\":2,\"timestamp\":1740000000002}";

    private static final String LINE_TOOL_CALL =
            "{\"id\":\"m-3c4d5e6f\",\"type\":\"TOOL_CALL\",\"content\":\"\",\"toolName\":\"read_file\","
                    + "\"toolArgs\":\"{\\\"path\\\":\\\"a.txt\\\"}\",\"toolResult\":\"\",\"subagentName\":\"\","
                    + "\"images\":[],\"seq\":3,\"timestamp\":1740000000003}";

    private Path writeTranscript(Path sessionDir, String... lines) throws IOException {
        Path file = sessionDir.resolve(SessionTranscriptStore.FILE_NAME);
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    @DisplayName("旧格式正常行：全部读出，条数与关键字段一致")
    void readsLegacyLinesIntact(@TempDir Path sessionDir) throws IOException {
        writeTranscript(sessionDir, LINE_USER, LINE_AI_TEXT, LINE_TOOL_CALL);

        List<BoxMessage> msgs = SessionTranscriptStore.read(sessionDir);

        assertEquals(3, msgs.size(), "旧格式三行必须全部读出");

        BoxMessage user = msgs.get(0);
        assertEquals("m-1a2b3c4d", user.getId());
        assertEquals(BoxMessage.Type.USER, user.getType());
        assertEquals("你好", user.getContent());
        assertEquals(1L, user.getSeq());
        assertEquals(1740000000001L, user.getTimestamp());
        assertTrue(user.getImages().isEmpty());

        BoxMessage ai = msgs.get(1);
        assertEquals(BoxMessage.Type.AI_TEXT, ai.getType());
        assertEquals("世界", ai.getContent());
        assertEquals(2L, ai.getSeq());

        BoxMessage call = msgs.get(2);
        assertEquals(BoxMessage.Type.TOOL_CALL, call.getType());
        assertEquals("read_file", call.getToolName());
        assertEquals("{\"path\":\"a.txt\"}", call.getToolArgs(), "工具入参必须原样回读");
        assertEquals(3L, call.getSeq());
    }

    @Test
    @DisplayName("向前兼容：含未知字段的行不丢失，已知字段照常解析")
    void unknownFieldDoesNotDropLine(@TempDir Path sessionDir) throws IOException {
        // 模拟新版本写出的行：多了 BoxMessage 尚未定义的字段（含嵌套对象与数组）
        String futureLine =
                "{\"id\":\"m-4d5e6f70\",\"type\":\"AI_TEXT\",\"content\":\"来自新版本\",\"seq\":2,"
                        + "\"timestamp\":1740000000004,\"tokenUsage\":{\"in\":10,\"out\":20},"
                        + "\"citations\":[\"a\",\"b\"],\"modelName\":\"gpt-next\"}";
        writeTranscript(sessionDir, LINE_USER, futureLine, LINE_TOOL_CALL);

        List<BoxMessage> msgs = SessionTranscriptStore.read(sessionDir);

        assertEquals(3, msgs.size(), "未知字段不得让整行被跳过（否则历史消息会悄悄变少）");
        BoxMessage future = msgs.get(1);
        assertEquals(BoxMessage.Type.AI_TEXT, future.getType());
        assertEquals("来自新版本", future.getContent());
        assertEquals(2L, future.getSeq());
        assertEquals(1740000000004L, future.getTimestamp());
    }

    @Test
    @DisplayName("向前兼容：含未知枚举值的行不丢失，type 降级为 null 而非整行报废")
    void unknownEnumValueDoesNotDropLine(@TempDir Path sessionDir) throws IOException {
        // 模拟新版本新增了 BoxMessage.Type 常量，旧代码读到时不认识
        String futureTypeLine =
                "{\"id\":\"m-5e6f7081\",\"type\":\"VOICE_CLIP\",\"content\":\"未来类型的正文\","
                        + "\"toolName\":\"\",\"toolArgs\":\"\",\"toolResult\":\"\",\"subagentName\":\"\","
                        + "\"images\":[],\"seq\":2,\"timestamp\":1740000000005}";
        writeTranscript(sessionDir, LINE_USER, futureTypeLine, LINE_TOOL_CALL);

        List<BoxMessage> msgs = SessionTranscriptStore.read(sessionDir);

        assertEquals(3, msgs.size(), "未知枚举值不得让整行被跳过");
        BoxMessage future = msgs.get(1);
        assertNull(future.getType(), "未知 type 降级为 null（前端按 type 精确匹配，不认识就不渲染）");
        assertEquals("未来类型的正文", future.getContent(), "正文等已知字段仍须完整保留");
        assertEquals(2L, future.getSeq());
        // 相邻的已知类型行不受污染
        assertEquals(BoxMessage.Type.USER, msgs.get(0).getType());
        assertEquals(BoxMessage.Type.TOOL_CALL, msgs.get(2).getType());
    }

    @Test
    @DisplayName("坏行容错：残缺 JSON 被跳过，其余行条数与内容不受影响")
    void corruptLineIsSkippedWithoutLosingOthers(@TempDir Path sessionDir) throws IOException {
        // 写一半就崩溃的行（append-only 文件尾部真实会出现这种残缺）
        String truncated = "{\"id\":\"m-6f708192\",\"type\":\"AI_TEXT\",\"content\":\"写到一半就崩";
        String notJson = "这根本不是 JSON";
        writeTranscript(sessionDir, LINE_USER, truncated, LINE_AI_TEXT, notJson, LINE_TOOL_CALL);

        List<BoxMessage> msgs = SessionTranscriptStore.read(sessionDir);

        assertEquals(3, msgs.size(), "两个坏行跳过，三个好行必须全部保住");
        assertEquals(BoxMessage.Type.USER, msgs.get(0).getType());
        assertEquals(BoxMessage.Type.AI_TEXT, msgs.get(1).getType());
        assertEquals("世界", msgs.get(1).getContent());
        assertEquals(BoxMessage.Type.TOOL_CALL, msgs.get(2).getType());
        assertEquals("read_file", msgs.get(2).getToolName());
    }

    @Test
    @DisplayName("空行与缺字段：空行忽略，缺失字段走 Java 字段初值而非 null")
    void blankLinesIgnoredAndMissingFieldsFallBackToDefaults(@TempDir Path sessionDir) throws IOException {
        // 极早期版本可能只写了最小字段集
        String minimal = "{\"type\":\"USER\",\"content\":\"最小行\",\"seq\":7}";
        writeTranscript(sessionDir, "", LINE_USER, "   ", minimal, "");

        List<BoxMessage> msgs = SessionTranscriptStore.read(sessionDir);

        assertEquals(2, msgs.size(), "空白行不算条数也不算坏行");
        BoxMessage min = msgs.get(1);
        assertEquals(BoxMessage.Type.USER, min.getType());
        assertEquals("最小行", min.getContent());
        assertEquals(7L, min.getSeq());
        assertEquals("", min.getToolName(), "缺失字段保持 Java 初值空串，避免下游 NPE");
        assertEquals("", min.getToolArgs());
        assertEquals("", min.getToolResult());
        assertEquals("", min.getSubagentName());
        assertNotNull(min.getImages(), "images 缺失时须为空集合而非 null");
        assertTrue(min.getImages().isEmpty());
        assertEquals(0L, min.getTimestamp());
    }
}
