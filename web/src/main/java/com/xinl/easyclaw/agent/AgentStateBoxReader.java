package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息盒子读取器：直接复用 AgentScope 的 agent_state.json（消息已全部持久化在那里），
 * 将其上下文（Msg 序列 + ContentBlock 时序）解析为 UI 可渲染的时序 BoxMessage 列表。
 * 不新增任何存储文件。
 */
public final class AgentStateBoxReader {

    private static final Logger log = LoggerFactory.getLogger(AgentStateBoxReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentStateBoxReader() {
    }

    /**
     * 从 agent_state.json 解析时序消息列表；文件不存在/解析失败返回空列表。
     */
    public static List<BoxMessage> read(Path agentStateJson) {
        List<BoxMessage> out = new ArrayList<>();
        if (agentStateJson == null || !Files.exists(agentStateJson)) {
            return out;
        }
        try {
            AgentState state = AgentState.fromJsonString(Files.readString(agentStateJson));
            if (state.getContext() == null) {
                return out;
            }
            long seq = 1;
            for (Msg m : state.getContext()) {
                if (m == null) {
                    continue;
                }
                // 跳过合成消息（压缩摘要、提醒等内部消息）
                Map<String, Object> meta = m.getMetadata();
                if (meta != null && Boolean.TRUE.equals(meta.get(Msg.METADATA_SYNTHETIC))) {
                    continue;
                }
                if (m.getRole() == MsgRole.USER) {
                    seq = parseUserMsg(m, out, seq);
                } else if (m.getRole() == MsgRole.ASSISTANT) {
                    seq = parseAssistantMsg(m, out, seq);
                }
                // SYSTEM/其他角色忽略
            }
        } catch (Exception e) {
            log.warn("解析 agent_state.json 失败 {}: {}", agentStateJson, e.getMessage());
        }
        return out;
    }

    private static long parseUserMsg(Msg m, List<BoxMessage> out, long seq) {
        String text = m.getTextContent();
        // 提取用户消息中的图片（截图/附件历史恢复）
        List<String> images = new ArrayList<>();
        if (m.getContent() != null) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof ImageBlock img) {
                    Source src = img.getSource();
                    if (src instanceof Base64Source b64) {
                        images.add("data:" + b64.getMediaType() + ";base64," + b64.getData());
                    } else if (src instanceof URLSource url) {
                        images.add(url.getUrl());
                    }
                }
            }
        }
        if ((text == null || text.isBlank()) && images.isEmpty()) {
            return seq;
        }
        // 上下文压缩的摘要消息（AgentScope 以用户消息形式插入，无 SYNTHETIC 标记）→ 按内容特征过滤
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("You are in the middle of a conversation")
                || trimmed.startsWith("The full conversation history has been saved")) {
            return seq;
        }
        // 工具确认的内部恢复消息（approved/denied/取消）不进入时序展示
        if ("approved".equals(trimmed) || "denied".equals(trimmed)
                || trimmed.contains("此前挂起的工具确认已被取消")) {
            return seq;
        }
        BoxMessage bm = new BoxMessage(BoxMessage.Type.USER, seq++);
        bm.setContent(text == null ? "" : text);
        if (!images.isEmpty()) {
            bm.setImages(images);
        }
        out.add(bm);
        return seq;
    }

    private static long parseAssistantMsg(Msg m, List<BoxMessage> out, long seq) {
        // 子 Agent 的消息：name 形如 "main/reviewer" → 折叠为 SUBAGENT 单条
        String name = m.getName();
        boolean fromSubagent = name != null && name.contains("/") && m.getRole() == MsgRole.ASSISTANT;
        if (fromSubagent) {
            String subName = name.substring(name.lastIndexOf('/') + 1);
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : m.getContent()) {
                String t = blockText(b);
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
            }
            BoxMessage bm = new BoxMessage(BoxMessage.Type.SUBAGENT, seq++);
            bm.setSubagentName(subName);
            bm.setContent(sb.toString().trim());
            out.add(bm);
            return seq;
        }
        // 主流程：按 ContentBlock 顺序输出（文本 → 思考 → 工具调用 → 工具结果）
        if (m.getContent() != null) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    String text = t.getText();
                    if (text != null && !text.isBlank()) {
                        BoxMessage bm = new BoxMessage(BoxMessage.Type.AI_TEXT, seq++);
                        bm.setContent(text);
                        out.add(bm);
                    }
                } else if (b instanceof ThinkingBlock th) {
                    String thinking = th.getThinking();
                    if (thinking != null && !thinking.isBlank()) {
                        BoxMessage bm = new BoxMessage(BoxMessage.Type.THINKING, seq++);
                        bm.setContent(thinking);
                        out.add(bm);
                    }
                } else if (b instanceof ToolUseBlock tu) {
                    BoxMessage bm = new BoxMessage(BoxMessage.Type.TOOL_CALL, seq++);
                    bm.setToolName(tu.getName() == null ? "" : tu.getName());
                    bm.setToolArgs(toJson(tu.getInput()));
                    out.add(bm);
                } else if (b instanceof ToolResultBlock tr) {
                    BoxMessage bm = new BoxMessage(BoxMessage.Type.TOOL_RESULT, seq++);
                    bm.setToolName(tr.getName() == null ? "" : tr.getName());
                    bm.setToolResult(blocksText(tr.getOutput()));
                    out.add(bm);
                }
            }
        }
        return seq;
    }

    private static String blockText(ContentBlock b) {
        if (b instanceof TextBlock t) {
            return t.getText();
        }
        if (b instanceof ThinkingBlock th) {
            return th.getThinking();
        }
        if (b instanceof ToolResultBlock tr) {
            return blocksText(tr.getOutput());
        }
        if (b instanceof ToolUseBlock tu) {
            return "[工具: " + tu.getName() + "] " + toJson(tu.getInput());
        }
        return null;
    }

    private static String blocksText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            String t = blockText(b);
            if (t != null && !t.isBlank()) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String toJson(Object o) {
        if (o == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
