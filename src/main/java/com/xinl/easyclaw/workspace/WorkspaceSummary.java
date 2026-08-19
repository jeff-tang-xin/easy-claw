package com.xinl.easyclaw.workspace;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class WorkspaceSummary {
    private String workspaceId;
    private String name;
    private String agentName;
    private String description;
    @JsonSerialize(using = ToStringSerializer.class)
    private java.nio.file.Path path;
    private String status;
    private Instant createdAt;
    private Instant lastAccessed;
    private String intent;
    private String scenarioId;
    private List<String> activeSkills;
}
