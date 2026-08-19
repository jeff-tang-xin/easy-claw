package com.xinl.easyclaw.workspace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SessionContext {
    private String sessionId;
    private String workspaceId;
    private String title;
    // Agent 由 RoleAgentFactory 在运行时按场景动态解析，不再在 Session 创建时实例化
    private Instant createdAt;
    private Instant lastAccessed;
    @Builder.Default
    private boolean restored = false;
}
