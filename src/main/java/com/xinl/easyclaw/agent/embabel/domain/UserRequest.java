package com.xinl.easyclaw.agent.embabel.domain;

public record UserRequest(
        String content,
        String workspaceId,
        String model
) {
    public UserRequest(String content) {
        this(content, null, null);
    }
}
