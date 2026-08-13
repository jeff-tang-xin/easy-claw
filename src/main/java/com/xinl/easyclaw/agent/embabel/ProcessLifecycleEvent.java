package com.xinl.easyclaw.agent.embabel;

/**
 * AgentProcess 生命周期事件 DTO
 * <p>
 * 用于在 EventBridge → AgentService → WebSocket 之间传递控制消息（非 Token 流）。
 */
public record ProcessLifecycleEvent(
        String processId,
        Type type,
        Object data
) {
    public enum Type {
        PLAN_FORMULATED,
        PAUSED,
        WAITING,
        COMPLETED,
        FAILED
    }
}
