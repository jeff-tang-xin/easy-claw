package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 Agent 步骤结构化落盘验证：subagent* 事件流经 TranscriptRecorder 聚合后，
 * SUBAGENT 条目除纯文本正文外还带 subagentSteps（与前端实时通道 steps 同构），
 * 历史回放据此重建 steps 时间线。
 */
class TranscriptRecorderStepsTest {

    @TempDir
    Path tmp;

    @Test
    void subagentStepsPersistWithTimelineOrder() {
        TranscriptRecorder rec = new TranscriptRecorder(tmp, evt -> { });
        String n = "coder";
        // 思考 → 文本 → 工具调用（入参两片）→ 结果 → 继续文本 → 结束
        rec.accept(StreamEvent.subagent(n));
        rec.accept(StreamEvent.subagentReasoning(n, "先看任务"));
        rec.accept(StreamEvent.subagentText(n, "好的"));
        rec.accept(StreamEvent.subagentTool(n, "listTasks"));
        rec.accept(StreamEvent.subagentToolArgs(n, "{\"limit\":"));
        rec.accept(StreamEvent.subagentToolArgs(n, "10}"));
        rec.accept(StreamEvent.subagentToolResult(n, "SUCCESS", "任务板空"));
        rec.accept(StreamEvent.subagentText(n, "干完了"));
        rec.accept(StreamEvent.subagentEnd(n));
        rec.flushAll();

        List<BoxMessage> all = SessionTranscriptStore.read(tmp);
        BoxMessage sub = all.stream()
                .filter(b -> b.getType() == BoxMessage.Type.SUBAGENT)
                .findFirst().orElseThrow();
        assertEquals("coder", sub.getSubagentName());
        List<BoxMessage.SubStepBox> steps = sub.getSubagentSteps();
        assertNotNull(steps, "结构化步骤应随条落盘");
        assertEquals(4, steps.size(), "思考/文本/工具/文本 四步: " + steps.size());
        assertEquals("reasoning", steps.get(0).getKind());
        assertEquals("先看任务", steps.get(0).getContent());
        assertEquals("text", steps.get(1).getKind());
        assertEquals("好的", steps.get(1).getContent());
        assertEquals("tool", steps.get(2).getKind());
        assertEquals("listTasks", steps.get(2).getName());
        assertEquals("{\"limit\":10}", steps.get(2).getArgs(), "入参两片应拼接");
        assertEquals("任务板空", steps.get(2).getResult());
        assertEquals("SUCCESS", steps.get(2).getState());
        assertEquals("text", steps.get(3).getKind());
        assertEquals("干完了", steps.get(3).getContent());
        // 纯文本正文仍在（可读性兜底），且不含入参 JSON
        assertTrue(sub.getContent().contains("好的"));
        assertTrue(!sub.getContent().contains("\"limit\""), "入参 JSON 不得混入正文");
    }

    @Test
    void legacyTranscriptWithoutStepsStillReadable() {
        // 旧版本转录（无 subagentSteps 字段）读回：字段为 null，回放端退化
        String json = "{\"id\":\"m-1\",\"type\":\"SUBAGENT\",\"content\":\"正文\","
                + "\"subagentName\":\"coder\",\"seq\":1,\"timestamp\":1}";
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            BoxMessage bm = mapper.readValue(json, BoxMessage.class);
            assertEquals(BoxMessage.Type.SUBAGENT, bm.getType());
            assertNull(bm.getSubagentSteps(), "旧数据无步骤字段");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
