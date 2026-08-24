package com.xinl.easyclaw.agent.domain;

/**
 * 流式对话事件（从 AgentScope 事件流解析后推送给 UI）
 * <p>
 * type 取值：
 * <ul>
 *   <li>reasoning - 推理过程增量</li>
 *   <li>text - 回复正文增量</li>
 *   <li>tool - 工具调用开始（content 为工具名）</li>
 *   <li>subagent - 子 Agent 开始工作（content 为子 Agent 名称）</li>
 *   <li>context - 上下文状态（content 为 JSON：消息数/token/是否已压缩）</li>
 *   <li>end - 回复完成</li>
 *   <li>stopped - 用户主动停止（区别于 end：不触发前端队列自动发送）</li>
 *   <li>error - 执行出错（content 为错误信息）</li>
 * </ul>
 */
public record StreamEvent(
        String type,
        String content
) {

    public static StreamEvent reasoning(String content) {
        return new StreamEvent("reasoning", content);
    }

    public static StreamEvent text(String content) {
        return new StreamEvent("text", content);
    }

    public static StreamEvent tool(String toolName) {
        return new StreamEvent("tool", toolName);
    }

    /** 工具调用参数（追溯） */
    public static StreamEvent toolArgs(String args) {
        return new StreamEvent("tool_args", args);
    }

    /** 工具调用结果（追溯，含状态） */
    public static StreamEvent toolResult(String content) {
        return new StreamEvent("tool_result", content);
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

    public static StreamEvent subagent(String agentName) {
        return new StreamEvent("subagent", agentName);
    }

    /** 子 Agent 输出增量（content 编码：agentName + \u0001 + delta） */
    public static StreamEvent subagentText(String agentName, String delta) {
        return new StreamEvent("subagent_text", agentName + "\u0001" + delta);
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
