package com.xinl.easyclaw.agent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 流式对话事件（从 AgentScope 事件流解析后推送给 UI）
 * <p>
 * type 取值：
 * <ul>
 *   <li>reasoning - 推理过程增量</li>
 *   <li>text - 回复正文增量</li>
 *   <li>tool - 工具调用开始（content 为工具名）</li>
 *   <li>file_changed - 工作区文件被写类工具修改（content 为相对路径）</li>
 *   <li>subagent - 子 Agent 开始工作（content 为子 Agent 名称）</li>
 *   <li>context - 上下文状态（content 为 JSON：消息数/token/是否已压缩）</li>
 *   <li>end - 回复完成</li>
 *   <li>stopped - 用户主动停止（区别于 end：不触发前端队列自动发送）</li>
 *   <li>error - 执行出错（content 为错误信息）</li>
 * </ul>
 *
 * <p>{@code toolCallId} 仅在工具类事件（tool / tool_args / tool_result / tool_end）上非空，
 * 取自框架事件的 {@code getToolCallId()}，用于前端把「同一次工具调用」的开始、入参、结果、
 * 结束四个事件精确配对。并发工具调用（多个 tool_call 交错返回）下，按「最后一个工具段」
 * 或按工具名匹配都会错关卡片：先返回的结果会抢走后发起卡片的槽位，导致先发起的那张卡
 * 永久停留在「执行中」。为 null 时前端退化为旧的就近匹配策略（兼容历史转录回放）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(
        String type,
        String content,
        String toolCallId
) {

    /** 非工具事件：无 toolCallId */
    public StreamEvent(String type, String content) {
        this(type, content, null);
    }

    public static StreamEvent reasoning(String content) {
        return new StreamEvent("reasoning", content);
    }

    public static StreamEvent text(String content) {
        return new StreamEvent("text", content);
    }

    public static StreamEvent tool(String toolName) {
        return new StreamEvent("tool", toolName);
    }

    /** 工具调用开始（带调用 id，供前端精确配对并发调用） */
    public static StreamEvent tool(String toolName, String toolCallId) {
        return new StreamEvent("tool", toolName, toolCallId);
    }

    /** 工具调用参数（追溯） */
    public static StreamEvent toolArgs(String args) {
        return new StreamEvent("tool_args", args);
    }

    /** 工具调用参数（追溯，带调用 id） */
    public static StreamEvent toolArgs(String args, String toolCallId) {
        return new StreamEvent("tool_args", args, toolCallId);
    }

    /** 工具调用结果（追溯，含状态） */
    public static StreamEvent toolResult(String content) {
        return new StreamEvent("tool_result", content);
    }

    /** 工具调用结果（追溯，含状态，带调用 id） */
    public static StreamEvent toolResult(String content, String toolCallId) {
        return new StreamEvent("tool_result", content, toolCallId);
    }

    /** 工具执行需用户确认（content 为 JSON：replyId + 待确认工具列表） */
    public static StreamEvent confirm(String json) {
        return new StreamEvent("confirm", json);
    }

    /** 工具已获授权（回合/永久），无需弹窗（自动放行） */
    public static StreamEvent autoConfirm() {
        return new StreamEvent("auto_confirm", "");
    }

    /** 挂起确认查询结果（前端轮询兜底；content 为 JSON：{pending, tools:[{id,name,input}]}） */
    public static StreamEvent pendingInfo(String json) {
        return new StreamEvent("pending_info", json);
    }

    /** 工具执行结束（配套 tool 事件；content 为工具名） */
    public static StreamEvent toolEnd(String toolName) {
        return new StreamEvent("tool_end", toolName);
    }

    /** 工具执行结束（配套 tool 事件，带调用 id：前端据此精确关闭对应卡片） */
    public static StreamEvent toolEnd(String toolName, String toolCallId) {
        return new StreamEvent("tool_end", toolName, toolCallId);
    }

    /**
     * 工作区文件发生变更（content 为相对工作区根目录的路径，'/' 分隔）。
     * <p>
     * 由写类工具（write_file / edit_file 等）成功执行后推送，供前端刷新文件树与
     * 已打开的预览标签页，避免用户必须手动点「刷新」才能看到 Agent 的改动。
     * <p>
     * 只携带路径而不携带文件内容：内容仍走 {@code /api/workspaces/{id}/file-content}
     * 按需拉取，既复用了该端点的沙箱校验与大小截断，也避免把大文件塞进 WS 帧。
     */
    public static StreamEvent fileChanged(String relativePath) {
        return new StreamEvent("file_changed", relativePath);
    }

    public static StreamEvent subagent(String agentName) {
        return new StreamEvent("subagent", agentName);
    }

    /** 子 Agent 输出增量（content 编码：agentName + \u0001 + delta） */
    public static StreamEvent subagentText(String agentName, String delta) {
        return new StreamEvent("subagent_text", agentName + "\u0001" + delta);
    }

    /** 子 Agent 思考增量（content 编码：agentName + \u0001 + delta） */
    public static StreamEvent subagentReasoning(String agentName, String delta) {
        return new StreamEvent("subagent_reasoning", agentName + "\u0001" + delta);
    }

    /** 子 Agent 开始调用工具（content 编码：agentName + \u0001 + toolName） */
    public static StreamEvent subagentTool(String agentName, String toolName) {
        return new StreamEvent("subagent_tool", agentName + "\u0001" + toolName);
    }

    /** 子 Agent 工具入参增量（content 编码：agentName + \u0001 + delta） */
    public static StreamEvent subagentToolArgs(String agentName, String delta) {
        return new StreamEvent("subagent_tool_args", agentName + "\u0001" + delta);
    }

    /**
     * 子 Agent 工具执行结果（content 编码：agentName + \u0001 + state + \u0001 + result）。
     * state 取值同主流程（SUCCESS / ERROR 等），供前端按状态着色。
     */
    public static StreamEvent subagentToolResult(String agentName, String state, String result) {
        return new StreamEvent("subagent_tool_result",
                agentName + "\u0001" + state + "\u0001" + result);
    }

    /** 子 Agent 结束运行（配套 subagent 事件；content 为子 Agent 名） */
    public static StreamEvent subagentEnd(String agentName) {
        return new StreamEvent("subagent_end", agentName);
    }

    public static StreamEvent context(String json) {
        return new StreamEvent("context", json);
    }

    public static StreamEvent end() {
        return new StreamEvent("end", "");
    }

    /**
     * 用户主动停止导致的回合终止。
     * <p>
     * 与 {@link #end()} 区分：end 表示回合自然完成，前端会据此自动发送 messageQueue 里的
     * 下一条消息；而「停止」是明确的中止意图，若复用 end 会立刻拉起新一轮回复，与语义相反。
     * 前端收到 stopped 只复位 UI 状态，不触发队列 flush。
     */
    public static StreamEvent stopped() {
        return new StreamEvent("stopped", "");
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", message);
    }

    /**
     * 会话状态事件（WS register 时主动回推；content 为 JSON：{running, pending, pendingTools:[...]}）
     */
    public static StreamEvent status(String json) {
        return new StreamEvent("status", json);
    }
}
