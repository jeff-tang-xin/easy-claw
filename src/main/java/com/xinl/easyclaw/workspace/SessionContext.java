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
    /* TODO: migrate to Embabel */
    private Object agent;
    private Instant createdAt;
    private Instant lastAccessed;
    @Builder.Default
    private boolean restored = false;
}
