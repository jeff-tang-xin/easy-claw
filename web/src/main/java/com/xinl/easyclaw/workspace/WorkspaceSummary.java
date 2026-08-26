package com.xinl.easyclaw.workspace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class WorkspaceSummary {
    private String workspaceId;
    private String name;
    /** 主智能体显示名（优先取主角色 displayName，兜底 workspace name） */
    private String agentName;
    private String description;
    private String path;
    private String status;
    private Instant createdAt;
    private Instant lastAccessed;
}
