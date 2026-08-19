package com.xinl.easyclaw.workspace;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class WorkspaceContext {
    private String workspaceId;
    private String userId;
    private String name;
    private String description;
    @JsonSerialize(using = ToStringSerializer.class)
    private Path path;
    // Agent 由 RoleAgentFactory 在运行时按场景动态解析，不再在 Workspace 创建时实例化
    private Instant createdAt;
    private Instant lastAccessed;
    @Builder.Default
    private boolean restored = false;
    private Map<String, Object> metadata;
    @Builder.Default
    private String intent = "general";
    private List<String> activeSkills;
    private String scenarioId;
}
