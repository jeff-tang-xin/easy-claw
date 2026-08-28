package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现并锁定缺陷：子 Agent 的「工具入参」增量被原样追加进子 Agent 正文缓冲，
 * 历史回放时以截断的原始 JSON 泄漏到界面正文（如 {@code rs": 4000, "url":}）。
 * <p>
 * 期望语义：工具入参属于「工具步」的附属数据，转录纯文本视图里不应污染正文；
 * 工具名与结果状态摘要仍需保留，保证历史可读。
 */
class TranscriptSubagentToolArgsTest {

    private static final char SEP = '\u0001';

    /** 收集落盘的转录条目 */
    private List<BoxMessage> replay(Path dir, Consumer<TranscriptRecorder> script) {
        TranscriptRecorder rec = new TranscriptRecorder(dir, e -> { });
        script.accept(rec);
        rec.flushAll();
        return SessionTranscriptStore.read(dir);
    }

    @Test
    @DisplayName("子 Agent 工具入参不得泄漏进转录正文")
    void toolArgsMustNotLeakIntoContent(@TempDir Path dir) {
        Path session = dir.resolve("s1");
        String agent = "researcher";
        // 一段真实形态的入参增量：分片流式到达，含会被截断的 JSON 片段
        String argsChunk1 = "{\"query\":\"spring boot\",\"maxCha";
        String argsChunk2 = "rs\": 4000, \"url\": \"https://x\"}";

        List<BoxMessage> out = replay(session, rec -> {
            rec.accept(StreamEvent.subagent(agent));
            rec.accept(StreamEvent.subagentText(agent, "我先检索一下。"));
            rec.accept(StreamEvent.subagentTool(agent, "web_search"));
            rec.accept(StreamEvent.subagentToolArgs(agent, argsChunk1));
            rec.accept(StreamEvent.subagentToolArgs(agent, argsChunk2));
            rec.accept(StreamEvent.subagentToolResult(agent, "SUCCESS", "命中 5 条"));
            rec.accept(StreamEvent.subagentText(agent, "检索完成。"));
            rec.accept(StreamEvent.subagentEnd(agent));
        });

        assertEquals(1, out.size(), "应聚合为一条 SUBAGENT 记录");
        BoxMessage bm = out.get(0);
        assertEquals(BoxMessage.Type.SUBAGENT, bm.getType());
        assertEquals(agent, bm.getSubagentName());

        String content = bm.getContent();
        // 缺陷断言：原始入参 JSON 片段绝不能出现在正文里
        assertFalse(content.contains(argsChunk1),
                "工具入参分片泄漏进正文: " + content);
        assertFalse(content.contains("rs\": 4000"),
                "被截断的入参 JSON 泄漏进正文（截图中的乱码来源）: " + content);
        assertFalse(content.contains("maxCha"),
                "入参键名泄漏进正文: " + content);

        // 正向断言：正文与结构化摘要仍完整可读
        assertTrue(content.contains("我先检索一下。"), "子 Agent 正文丢失: " + content);
        assertTrue(content.contains("检索完成。"), "子 Agent 后续正文丢失: " + content);
        assertTrue(content.contains("web_search"), "工具名摘要丢失: " + content);
        assertTrue(content.contains("SUCCESS"), "工具结果状态摘要丢失: " + content);
    }

    @Test
    @DisplayName("思考标记只在段落开头出现一次，不得逐分片重复")
    void reasoningMarkerNotRepeatedPerChunk(@TempDir Path dir) {
        Path session = dir.resolve("s3");
        List<BoxMessage> out = replay(session, rec -> {
            rec.accept(StreamEvent.subagent("planner"));
            // 推理按小分片流式到达（真实场景每个 token 一个事件）
            rec.accept(StreamEvent.subagentReasoning("planner", "我"));
            rec.accept(StreamEvent.subagentReasoning("planner", "需要"));
            rec.accept(StreamEvent.subagentReasoning("planner", "先拆解任务。"));
            rec.accept(StreamEvent.subagentText("planner", "方案如下。"));
            rec.accept(StreamEvent.subagentEnd("planner"));
        });
        String content = out.get(0).getContent();
        // 🧠 是代理对，用 codePoint 统计
        long marks = content.codePoints().filter(c -> c == 0x1F9E0).count();
        assertEquals(1, marks,
                "思考标记被逐分片重复了 " + marks + " 次: " + content);
        assertTrue(content.contains("我需要先拆解任务。"),
                "思考内容被标记割裂: " + content);
    }

    @Test
    @DisplayName("入参分隔符编码非法时不得崩溃且不产生残留")
    void malformedArgsEventIsIgnored(@TempDir Path dir) {
        Path session = dir.resolve("s2");
        List<BoxMessage> out = replay(session, rec -> {
            rec.accept(StreamEvent.subagent("coder"));
            rec.accept(StreamEvent.subagentText("coder", "开始。"));
            // 无分隔符的畸形事件（不应改变任何缓冲）
            rec.accept(new StreamEvent("subagent_tool_args", "没有分隔符的原始串"));
            rec.accept(StreamEvent.subagentEnd("coder"));
        });
        assertEquals(1, out.size());
        String content = out.get(0).getContent();
        assertEquals("开始。", content, "畸形入参事件污染了正文: " + content);
    }
}
