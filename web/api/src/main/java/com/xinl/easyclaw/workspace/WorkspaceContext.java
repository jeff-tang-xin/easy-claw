package com.xinl.easyclaw.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.Instant;
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
    @JsonIgnore
    private HarnessAgent agent;
    private Instant createdAt;
    private Instant lastAccessed;
    @Builder.Default
    private boolean restored = false;
    private Map<String, Object> metadata;
    /** 归属的 hub 项目（cloud 模式）：同步知识库/黑板时携带；与 workspaces.project_id 一致，缓存冗余便于工具读取。 */
    private Long projectId;
}
