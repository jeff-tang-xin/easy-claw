package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.StreamEvent;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 转录记录器：包装推送给 UI 的 StreamEvent 消费者，
 * 把增量事件（text/reasoning/tool/subagent）聚合为完整 BoxMessage 追加到会话转录。
 * <p>
 * 聚合规则（与前端实时渲染逻辑一致）：
 * <ul>
 *   <li>text / reasoning 增量分别累积，遇工具/子代理/回合结束时成条落盘</li>
 *   <li>tool → tool_args → tool_result → tool_end 组成 TOOL_CALL + TOOL_RESULT 一对</li>
 *   <li>subagent / subagent_text（name\u0001delta 编码）/ subagent_end 组成 SUBAGENT 一条</li>
 *   <li>confirm（人工确认暂停）：先把未完成的工具调用落盘——原事件流会被释放，
 *       结果由恢复流的新记录器补写 TOOL_RESULT（前端历史加载支持孤立结果配对）</li>
 * </ul>
 * 落盘失败不影响对话主流程。
 */
final class TranscriptRecorder implements Consumer<StreamEvent> {

    /** subagent_text 事件中名称与增量的分隔符（与 StreamEvent.subagentText 编码一致） */
    private static final char SEP = '\u0001';

    private final Path sessionDir;
    private final Consumer<StreamEvent> delegate;
    private long seq;

    private final StringBuilder textBuf = new StringBuilder();
    private final StringBuilder thinkBuf = new StringBuilder();
    private String toolName;
    private final StringBuilder toolArgs = new StringBuilder();
    private final StringBuilder toolResult = new StringBuilder();
    private String subagentName;
    private final StringBuilder subBuf = new StringBuilder();

    TranscriptRecorder(Path sessionDir, Consumer<StreamEvent> delegate) {
        this.sessionDir = sessionDir;
        this.delegate = delegate;
        this.seq = SessionTranscriptStore.countEntries(sessionDir);
    }

    @Override
    public void accept(StreamEvent evt) {
        if (evt != null) {
            try {
                record(evt);
            } catch (Exception ignore) {
                // 转录绝不能影响主流程
            }
        }
        delegate.accept(evt);
    }

    /** 回合结束/出错/流终止：把所有累积缓冲落盘（幂等） */
    void flushAll() {
        flushText();
        flushTool();
        flushSubagent();
    }

    private void record(StreamEvent evt) {
        switch (evt.type()) {
            case "text" -> textBuf.append(nullSafe(evt.content()));
            case "reasoning" -> thinkBuf.append(nullSafe(evt.content()));
            case "tool" -> {
                flushText();
                flushTool();
                toolName = evt.content();
                toolArgs.setLength(0);
                toolResult.setLength(0);
            }
            case "tool_args" -> toolArgs.append(nullSafe(evt.content()));
            case "tool_result" -> toolResult.append(nullSafe(evt.content()));
            case "tool_end" -> flushTool();
            case "subagent" -> {
                flushText();
                flushSubagent();
                subagentName = evt.content();
                subBuf.setLength(0);
            }
            case "subagent_text" -> {
                String c = evt.content();
                int i = c == null ? -1 : c.indexOf(SEP);
                if (i >= 0) {
                    String name = c.substring(0, i);
                    String delta = c.substring(i + 1);
                    if (subagentName == null || !subagentName.equals(name)) {
                        flushSubagent();
                        subagentName = name;
                    }
                    subBuf.append(delta);
                }
            }
            case "subagent_end" -> flushSubagent();
            case "confirm" -> {
                // 人工确认暂停：原流将被释放，先把未完成工具调用固化（结果由恢复流补写）
                flushText();
                flushTool();
            }
            case "end", "error" -> flushAll();
            default -> {
                // context/auto_confirm/pending_info/status 等瞬态事件不入转录
            }
        }
    }

    private void flushText() {
        if (thinkBuf.length() > 0) {
            BoxMessage bm = new BoxMessage(BoxMessage.Type.THINKING, ++seq);
            bm.setContent(thinkBuf.toString());
            SessionTranscriptStore.append(sessionDir, bm);
            thinkBuf.setLength(0);
        }
        if (textBuf.length() > 0) {
            BoxMessage bm = new BoxMessage(BoxMessage.Type.AI_TEXT, ++seq);
            bm.setContent(textBuf.toString());
            SessionTranscriptStore.append(sessionDir, bm);
            textBuf.setLength(0);
        }
    }

    private void flushTool() {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        BoxMessage call = new BoxMessage(BoxMessage.Type.TOOL_CALL, ++seq);
        call.setToolName(toolName);
        call.setToolArgs(toolArgs.toString());
        SessionTranscriptStore.append(sessionDir, call);
        if (toolResult.length() > 0) {
            BoxMessage res = new BoxMessage(BoxMessage.Type.TOOL_RESULT, ++seq);
            res.setToolName(toolName);
            res.setToolResult(toolResult.toString());
            SessionTranscriptStore.append(sessionDir, res);
        }
        toolName = null;
        toolArgs.setLength(0);
        toolResult.setLength(0);
    }

    private void flushSubagent() {
        if (subagentName == null) {
            return;
        }
        if (subBuf.length() > 0) {
            BoxMessage bm = new BoxMessage(BoxMessage.Type.SUBAGENT, ++seq);
            bm.setSubagentName(subagentName);
            bm.setContent(subBuf.toString());
            SessionTranscriptStore.append(sessionDir, bm);
        }
        subagentName = null;
        subBuf.setLength(0);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
