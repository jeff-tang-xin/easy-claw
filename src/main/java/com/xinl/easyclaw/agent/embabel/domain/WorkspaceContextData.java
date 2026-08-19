package com.xinl.easyclaw.agent.embabel.domain;

import com.embabel.common.ai.model.LlmOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record WorkspaceContextData(
        String workspaceId,
        Path workspacePath,
        String userId,
        String roleName,
        String roleSystemPrompt,
        String roleModel,
        Double roleTemperature,
        List<String> allowedAgentTypes,
        List<String> allowedMcpTools,
        List<String> disabledTools,
        List<McpToolBridge> mcpTools,
        List<SubagentDeclaration> subagentDeclarations,
        String intent,
        List<String> activeSkills,
        String scenarioId
) {
    public WorkspaceContextData(String workspaceId, Path workspacePath, String userId) {
        this(workspaceId, workspacePath, userId,
                null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                "general", List.of(), null);
    }

    public WorkspaceContextData withRole(String roleName, String roleSystemPrompt,
                                          String roleModel, Double roleTemperature) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabledTools, mcpTools, subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withAllowedAgentTypes(List<String> allowedAgentTypes) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes == null ? List.of() : List.copyOf(allowedAgentTypes),
                allowedMcpTools,
                disabledTools, mcpTools, subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withAllowedMcpTools(List<String> allowedMcpTools) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes,
                allowedMcpTools == null ? List.of() : List.copyOf(allowedMcpTools),
                disabledTools, mcpTools, subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withDisabledTools(List<String> disabled) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabled == null ? List.of() : List.copyOf(disabled),
                mcpTools, subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withMcpTools(List<McpToolBridge> tools) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabledTools, tools == null ? List.of() : List.copyOf(tools),
                subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withSubagents(List<SubagentDeclaration> decls) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabledTools, mcpTools,
                decls == null ? List.of() : List.copyOf(decls),
                intent, activeSkills, scenarioId);
    }

    public WorkspaceContextData withIntent(String intent, List<String> activeSkills) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabledTools, mcpTools, subagentDeclarations,
                intent == null ? "general" : intent,
                activeSkills == null ? List.of() : List.copyOf(activeSkills),
                scenarioId);
    }

    public WorkspaceContextData withScenarioId(String scenarioId) {
        return new WorkspaceContextData(workspaceId, workspacePath, userId,
                roleName, roleSystemPrompt, roleModel, roleTemperature,
                allowedAgentTypes, allowedMcpTools,
                disabledTools, mcpTools, subagentDeclarations,
                intent, activeSkills, scenarioId);
    }

    public LlmOptions resolveLlmOptions() {
        LlmOptions opts;
        if (roleModel != null && !roleModel.isBlank()) {
            opts = LlmOptions.Companion.withModel(roleModel);
        } else {
            opts = LlmOptions.Companion.withDefaults();
        }
        opts.setTimeout(Duration.ofSeconds(60));
        if (roleTemperature != null) {
            opts.setTemperature(roleTemperature);
        }
        return opts;
    }

    public record McpToolBridge(String toolName, String description,
                                String httpMethod, String urlTemplate,
                                String bodyMode, String bodyTemplate,
                                java.util.Map<String, String> headers) {}

    public record SubagentDeclaration(String name, String description, String systemPrompt,
                                      String roleType, String agentClassName) {
        public SubagentDeclaration(String name, String description, String systemPrompt) {
            this(name, description, systemPrompt, "MARKDOWN", null);
        }
    }
}
