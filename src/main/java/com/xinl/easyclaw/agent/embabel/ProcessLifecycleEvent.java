package com.xinl.easyclaw.agent.embabel;

/**
 * AgentProcess 生命周期事件 DTO
 * <p>
 * 用于 EventBridge → AgentService → WebSocket 之间传递控制消息（非 Token 流）。
 */
public record ProcessLifecycleEvent(
        String processId,
        Type type,
        Object data
) {
    public enum Type {
        PLAN_FORMULATED,
        ACTION_START,
        ACTION_END,
        TOOL_CALL_START,
        TOOL_CALL_END,
        LLM_CALL_START,
        LLM_CALL_END,
        LLM_INVOCATION,
        LLM_THINKING,
        PROGRESS_UPDATE,
        GOAL_ACHIEVED,
        TOOL_LOOP_START,
        TOOL_LOOP_COMPLETED,
        STATE_TRANSITION,
        REPLAN_REQUESTED,
        PAUSED,
        WAITING,
        STUCK,
        COMPLETED,
        FAILED
    }
}
