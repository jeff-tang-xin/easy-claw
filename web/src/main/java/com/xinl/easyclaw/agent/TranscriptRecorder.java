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
 *   <li>subagent / subagent_text（name\u0001delta 编码）/ subagent_end 组成 SUBAGENT 一条；
 *       其中 subagent_tool_args（工具入参原始 JSON 分片）只参与归属跟踪、不写入正文，
 *       否则历史回放会把截断的 JSON 当作子 Agent 话术渲染出来</li>
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
    /** 当前子 Agent 段落的类型（text/reasoning/tool/toolResult），用于只在切换时插入一次可读标记 */
    private String subSegKind;

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
                subSegKind = null;
            }
            case "subagent_text" -> appendSubagent(evt.content(), "text", "");
            // 结构化子 Agent 事件：转录里退化为带标记的纯文本，保证历史回放仍可读。
            // 标记只在「切换到该类型」时插入一次 —— 早期实现按分片插入，
            // 流式下会得到「🧠 我🧠 需要🧠 先…」这种被标记割裂的正文。
            case "subagent_reasoning" -> appendSubagent(evt.content(), "reasoning", "🧠 ");
            case "subagent_tool" -> appendSubagent(evt.content(), "tool", "\n🔧 调用: ");
            // 工具入参属于「工具步」的附属数据，不是子 Agent 的话术：
            // 早期实现把入参增量原样追加进正文缓冲，历史回放时会把截断的原始 JSON
            // （形如 rs": 4000, "url":）当正文渲染出来。此处只做归属跟踪，不写正文。
            case "subagent_tool_args" -> trackSubagent(evt.content());
            case "subagent_tool_result" -> {
                // 编码为 name \u0001 state \u0001 result，转录只保留状态摘要
                String c = evt.content();
                int i = c == null ? -1 : c.indexOf(SEP);
                if (i >= 0) {
                    String rest = c.substring(i + 1);
                    int j = rest.indexOf(SEP);
                    String state = j >= 0 ? rest.substring(0, j) : rest;
                    appendSubagent(c.substring(0, i) + SEP + "\n📤 结果(" + state + ")\n",
                            "toolResult", "");
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

    /**
     * 只跟踪子 Agent 归属、不写入正文缓冲。
     * <p>
     * 用于「工具入参」这类附属数据事件：它们必须参与切换检测（否则跨子 Agent 的
     * 边界会错位），但其内容是原始 JSON 分片，写进纯文本正文就会变成界面上的乱码。
     *
     * @return 解析出的子 Agent 名；编码非法时返回 null
     */
    private String trackSubagent(String content) {
        int i = content == null ? -1 : content.indexOf(SEP);
        if (i < 0) {
            return null;
        }
        String name = content.substring(0, i);
        if (subagentName == null || !subagentName.equals(name)) {
            flushSubagent();
            subagentName = name;
        }
        return name;
    }

    /**
     * 追加一段子 Agent 增量。content 编码为 {@code name \u0001 delta}。
     * <p>
     * prefix 是该类型段落的可读标记（如思考的 🧠），只在「段落类型发生切换」时插入一次：
     * 流式增量按 token 到达，若每个分片都插入标记，正文会被割裂成
     * 「🧠 我🧠 需要🧠 先…」。切换子 Agent 时先固化上一段，避免两个子 Agent 的输出串条。
     *
     * @param kind   段落类型标识（text/reasoning/tool/toolResult），用于检测类型切换
     * @param prefix 段落起始标记
     */
    private void appendSubagent(String content, String kind, String prefix) {
        int i = content == null ? -1 : content.indexOf(SEP);
        if (i < 0) {
            return;
        }
        String delta = content.substring(i + 1);
        String name = trackSubagent(content);
        if (name == null) {
            return;
        }
        if (!kind.equals(subSegKind)) {
            subSegKind = kind;
            subBuf.append(prefix);
        }
        subBuf.append(delta);
    }

    private void flushText() {        if (thinkBuf.length() > 0) {
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
        subSegKind = null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
