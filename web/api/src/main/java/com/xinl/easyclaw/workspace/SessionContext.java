package com.xinl.easyclaw.workspace;

import io.agentscope.harness.agent.HarnessAgent;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SessionContext {
    private String sessionId;
    private String workspaceId;
    private String title;
    private HarnessAgent agent;
    private Instant createdAt;
    private Instant lastAccessed;
    @Builder.Default
    private boolean restored = false;
}
