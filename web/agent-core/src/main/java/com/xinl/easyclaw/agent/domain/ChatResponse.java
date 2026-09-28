package com.xinl.easyclaw.agent.domain;

/**
 * 聊天响应领域对象
 */
public record ChatResponse(
        String content,
        String format,
        String agentName
) {
}
