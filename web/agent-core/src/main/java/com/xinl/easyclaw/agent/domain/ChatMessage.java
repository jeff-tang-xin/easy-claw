package com.xinl.easyclaw.agent.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息模型（有序片段）
 * <p>
 * 一条消息 = 按时间顺序排列的片段列表（文本 / 工具调用 / 推理 / 子 Agent），
 * 工具调用与对话输出在同一时序中交织展示。
 */
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public static final String SEG_TEXT = "text";
    public static final String SEG_TOOL = "tool";
    public static final String SEG_REASONING = "reasoning";
    public static final String SEG_SUBAGENT = "subagent";
    public static final String SEG_IMAGE = "image";

    private final String role;
    private final String time;
    private final List<Segment> segments = new ArrayList<>();

    public ChatMessage(String role, String time) {
        this.role = role;
        this.time = time;
    }

    public ChatMessage(String role, String time, String text) {
        this(role, time);
        addSegment(SEG_TEXT, text);
    }

    /** 消息片段（可变 content，供流式增量） */
    public static class Segment {
        private final String type;
        private String content;

        public Segment(String type, String content) {
            this.type = type;
            this.content = content == null ? "" : content;
        }

        public String getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public void appendContent(String delta) {
            this.content += delta;
        }
    }

    public void addSegment(String type, String content) {
        segments.add(new Segment(type, content));
    }

    public void addText(String text) {
        addSegment(SEG_TEXT, text);
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public String getRole() {
        return role;
    }

    public String getTime() {
        return time;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}
