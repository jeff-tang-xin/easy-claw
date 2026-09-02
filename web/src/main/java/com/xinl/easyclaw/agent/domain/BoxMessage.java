package com.xinl.easyclaw.agent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 消息盒子中的一条时序消息。
 * 一次对话的所有输出（用户问题、AI 正式输出、思考、工具调用/结果、子代理活动、确认请求、系统提示）
 * 都统一进盒子，按 seq 有序排列，可整体 JSON 序列化到磁盘（box.json）。
 */
public class BoxMessage {

    public enum Type {
        /** 用户输入 */
        USER,
        /** AI 正式输出（markdown） */
        AI_TEXT,
        /** 思考过程（可折叠） */
        THINKING,
        /** 工具调用（名称 + 参数，可折叠） */
        TOOL_CALL,
        /** 工具执行结果（可折叠） */
        TOOL_RESULT,
        /** 子代理活动（可折叠） */
        SUBAGENT,
        /** 共享记录本条目（team 模式并行子 Agent 登记的结论） */
        BLACKBOARD,
        /** 权限确认请求 */
        CONFIRM,
        /** 系统提示（错误/上下文压缩等） */
        SYSTEM
    }

    private String id;
    private Type type;
    private String content = "";
    private String toolName = "";
    private String toolArgs = "";
    private String toolResult = "";
    private String subagentName = "";
    /** 用户消息附带的图片（data URI 或 URL 列表），历史恢复时展示 */
    private List<String> images = new ArrayList<>();
    private long seq;
    private long timestamp;
    /** 流式中间态标志：true 表示该条仍在接收流式 chunk（不持久化） */
    private transient boolean streaming = false;

    public BoxMessage() {
    }

    public BoxMessage(Type type, long seq) {
        this.id = "m-" + UUID.randomUUID().toString().substring(0, 8);
        this.type = type;
        this.seq = seq;
        this.timestamp = System.currentTimeMillis();
    }

    public synchronized void appendContent(String chunk) {
        this.content += chunk;
    }

    public synchronized void appendArgs(String chunk) {
        this.toolArgs += chunk;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(String toolArgs) {
        this.toolArgs = toolArgs;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    public String getSubagentName() {
        return subagentName;
    }

    public void setSubagentName(String subagentName) {
        this.subagentName = subagentName;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }
}
