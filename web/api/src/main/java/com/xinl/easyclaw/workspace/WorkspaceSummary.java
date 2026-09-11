package com.xinl.easyclaw.workspace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class WorkspaceSummary {
    private String workspaceId;
    private String name;
    /** 主智能体显示名（优先取主控智能体 displayName，兜底 workspace name） */
    private String agentName;
    private String description;
    private String path;
    private String status;
    /** 工作区形态分类：single / team / schedule（与场景 mode 同值域） */
    private String type;
    private Instant createdAt;
    private Instant lastAccessed;
}
