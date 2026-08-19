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
 *   <li>plan - GOAP 计划已制定（content 为 JSON：{goal, totalSteps, steps:[{name,description}]}）</li>
 *   <li>step - 步骤状态更新（content 为 JSON：{name,description,status:running|done|failed,index,total,durationMs}）</li>
 *   <li>end - 回复完成</li>
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

    public static StreamEvent toolArgs(String args) {
        return new StreamEvent("tool_args", args);
    }

    public static StreamEvent toolResult(String content) {
        return new StreamEvent("tool_result", content);
    }

    public static StreamEvent confirm(String json) {
        return new StreamEvent("confirm", json);
    }

    public static StreamEvent autoConfirm() {
        return new StreamEvent("auto_confirm", "");
    }

    public static StreamEvent pendingInfo(String json) {
        return new StreamEvent("pending_info", json);
    }

    public static StreamEvent toolEnd(String toolName) {
        return new StreamEvent("tool_end", toolName);
    }

    public static StreamEvent subagent(String agentName) {
        return new StreamEvent("subagent", agentName);
    }

    public static StreamEvent subagentText(String agentName, String delta) {
        return new StreamEvent("subagent_text", agentName + "\u0001" + delta);
    }

    public static StreamEvent subagentEnd(String agentName) {
        return new StreamEvent("subagent_end", agentName);
    }

    public static StreamEvent context(String json) {
        return new StreamEvent("context", json);
    }

    /** GOAP 计划已制定；content 为 JSON：{goal, totalSteps, steps:[{name,description}]} */
    public static StreamEvent plan(String json) {
        return new StreamEvent("plan", json);
    }

    /** 步骤状态更新；content 为 JSON：{name,description,status,index,total,durationMs} */
    public static StreamEvent step(String json) {
        return new StreamEvent("step", json);
    }

    /** 工具调用详情；content 为 JSON：{action,tool,input,output,durationMs,status} */
    public static StreamEvent toolCall(String json) {
        return new StreamEvent("tool_call", json);
    }

    /** LLM 调用详情；content 为 JSON：{action,model,promptPreview,responsePreview,durationMs,status} */
    public static StreamEvent llmCall(String json) {
        return new StreamEvent("llm_call", json);
    }

    /** Action (step) 详情；content 为 JSON：{name,description,status,index,total,durationMs,processId,parentProcessId} */
    public static StreamEvent agentAction(String json) {
        return new StreamEvent("agent_action", json);
    }

    /** 子智能体生命周期；content 为 JSON：{name,status:start|end,durationMs?,processId,parentProcessId} */
    public static StreamEvent subagentLifecycle(String json) {
        return new StreamEvent("subagent_lifecycle", json);
    }

    public static StreamEvent end() {
        return new StreamEvent("end", "");
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", message);
    }

    public static StreamEvent status(String json) {
        return new StreamEvent("status", json);
    }
}
