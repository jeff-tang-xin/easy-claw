package com.xinl.easyclaw.api;

import com.xinl.easyclaw.config.SettingsService;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.memory.entity.PropositionEntity;
import com.xinl.easyclaw.memory.service.MemoryService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 管理类 API：Skills / 子 Agent、角色、工具、MCP、设置、记忆
 */
@RestController
@RequestMapping("/api")
public class ManageController {

    private final RoleManagementService roleService;
    private final ToolManagementService toolService;
    private final ToolRegistryService toolRegistryService;
    private final McpConnectionService mcpService;
    private final MemoryService memoryService;
    private final WorkspaceManager workspaceManager;
    private final SettingsService settingsService;

    public ManageController(RoleManagementService roleService,
                            ToolManagementService toolService,
                            ToolRegistryService toolRegistryService,
                            McpConnectionService mcpService,
                            MemoryService memoryService,
                            WorkspaceManager workspaceManager,
                            SettingsService settingsService) {
        this.roleService = roleService;
        this.toolService = toolService;
        this.toolRegistryService = toolRegistryService;
        this.mcpService = mcpService;
        this.memoryService = memoryService;
        this.workspaceManager = workspaceManager;
        this.settingsService = settingsService;
    }

    // ================= Skills & 子 Agent =================

    public record SkillFileDto(String scope, String name, String description, String path, String content) {
    }

    public record SkillWriteRequest(String scope, String workspaceId, String name, String description,
                                    String content, String fileName) {
    }

    @GetMapping("/skills")
    public List<SkillFileDto> listSkills(@RequestParam(required = false) String workspaceId) {
        List<SkillFileDto> result = new ArrayList<>();
        // global（~/.easyClaw/skills）
        collectMd(SystemHomePaths.globalSkillsDir(), "global", result);
        collectMd(SystemHomePaths.globalSubagentsDir(), "global-subagent", result);
        if (workspaceId != null) {
            WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
            if (ws != null) {
                Path agentRoot = ws.getPath().resolve(".easyClaw/agent");
                collectMd(agentRoot.resolve("skills"), "workspace", result);
                collectMd(agentRoot.resolve("subagents"), "workspace-subagent", result);
            }
        }
        return result;
    }

    private void collectMd(Path dir, String scope, List<SkillFileDto> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".md", "");
                        String desc = readDescription(p);
                        out.add(new SkillFileDto(scope, name, desc, p.toAbsolutePath().toString(), ""));
                    });
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private String readDescription(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("description:")) {
                    return t.substring("description:".length()).trim().replaceAll("^[\"']|[\"']$", "");
                }
            }
            if (!lines.isEmpty()) {
                return lines.get(0).trim();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        return "";
    }

    @PostMapping("/skills")
    public SkillFileDto createSkill(@RequestBody SkillWriteRequest req) {
        try {
            String safeName = (req.fileName() == null || req.fileName().isBlank())
                    ? (req.name() == null ? "skill" : req.name()) + ".md"
                    : req.fileName();
            if (!safeName.endsWith(".md")) {
                safeName = safeName + ".md";
            }
            Path dir;
            if ("workspace".equals(req.scope()) || "workspace-subagent".equals(req.scope()) || "workspace" == req.scope()) {
                WorkspaceContext ws = workspaceManager.getWorkspace(req.workspaceId());
                if (ws == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区不存在");
                }
                Path agentRoot = ws.getPath().resolve(".easyClaw/agent");
                dir = ("subagent".equals(req.scope())) ? agentRoot.resolve("subagents")
                        : agentRoot.resolve("skills");
            } else {
                dir = "subagent".equals(req.scope()) ? SystemHomePaths.globalSubagentsDir()
                        : SystemHomePaths.globalSkillsDir();
            }
            Files.createDirectories(dir);
            String content = req.content() == null || req.content().isBlank()
                    ? "---\ndescription: " + (req.description() == null ? "" : req.description()) + "\n---\n"
                    : req.content();
            Path file = dir.resolve(safeName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            if (req.workspaceId() != null) {
                workspaceManager.rebuildAgent(req.workspaceId());
            }
            return new SkillFileDto(req.scope(), safeName.replace(".md", ""),
                    req.description() == null ? "" : req.description(), file.toAbsolutePath().toString(), content);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/skills")
    public void deleteSkill(@RequestParam String path) {
        try {
            Path file = Path.of(path).normalize();
            if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".md")) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除失败: " + e.getMessage());
        }
    }

    // ================= 角色 =================

    @GetMapping("/roles")
    public List<AgentRoleEntity> roles() {
        return roleService.findAll();
    }

    @PostMapping("/roles")
    public AgentRoleEntity createRole(@RequestBody AgentRoleEntity role) {
        role.setId(null);
        AgentRoleEntity created = roleService.create(role);
        // 角色变更影响主智能体模型/提示词，重建所有 Workspace Agent
        workspaceManager.rebuildAllAgents();
        return created;
    }

    @PutMapping("/roles/{id}")
    public AgentRoleEntity updateRole(@PathVariable Long id, @RequestBody AgentRoleEntity role) {
        AgentRoleEntity updated = roleService.update(id, role);
        // 角色变更影响主智能体模型/提示词，重建所有 Workspace Agent
        workspaceManager.rebuildAllAgents();
        return updated;
    }

    @DeleteMapping("/roles/{id}")
    public void deleteRole(@PathVariable Long id) {
        roleService.delete(id);
        workspaceManager.rebuildAllAgents();
    }

    @PostMapping("/roles/{id}/active/{active}")
    public AgentRoleEntity setRoleActive(@PathVariable Long id, @PathVariable boolean active) {
        AgentRoleEntity updated = roleService.setActive(id, active);
        workspaceManager.rebuildAllAgents();
        return updated;
    }

    // ================= 工具 =================

    @GetMapping("/tools")
    public List<ToolDefinitionEntity> tools() {
        List<ToolDefinitionEntity> all = toolService.findAll();
        if (all.isEmpty()) {
            toolRegistryService.syncBuiltinTools();
            all = toolService.findAll();
        }
        return all;
    }

    @PutMapping("/tools/{id}/enabled/{enabled}")
    public ToolDefinitionEntity setToolEnabled(@PathVariable Long id, @PathVariable boolean enabled) {
        ToolDefinitionEntity t = toolService.setEnabled(id, enabled);
        // 工具启用状态影响 Agent Toolkit 过滤，重建所有已加载 Workspace 的 Agent 使其立即生效
        workspaceManager.rebuildAllAgents();
        return t;
    }

    @GetMapping("/tools/builtin")
    public List<ToolRegistryService.ToolDef> builtinTools() {
        return toolRegistryService.listBuiltinTools();
    }

    // ================= MCP =================

    @GetMapping("/mcp")
    public List<McpServiceEntity> mcp() {
        return mcpService.findAll();
    }

    @PostMapping("/mcp")
    public McpServiceEntity createMcp(@RequestBody McpServiceEntity entity) {
        entity.setId(null);
        return mcpService.create(entity);
    }

    @PutMapping("/mcp/{id}")
    public McpServiceEntity updateMcp(@PathVariable Long id, @RequestBody McpServiceEntity entity) {
        return mcpService.update(id, entity);
    }

    @DeleteMapping("/mcp/{id}")
    public void deleteMcp(@PathVariable Long id) {
        mcpService.delete(id);
    }

    @PostMapping("/mcp/{id}/connect")
    public McpServiceEntity connectMcp(@PathVariable Long id) {
        return mcpService.connect(id);
    }

    // ================= 设置（YAML 原文编辑器） =================

    public record SettingsYamlResponse(String yaml, String settingsFile, String hotReloadNote) {
    }

    public record SettingsYamlRequest(String yaml) {
    }

    @GetMapping("/settings")
    public SettingsYamlResponse settings() {
        return new SettingsYamlResponse(
                settingsService.readRawYaml(),
                settingsService.getExternalConfigPath().toString(),
                "保存后 agentscope 模型、日志级别热生效；server.port 需重启；其他 Spring 配置下次启动生效");
    }

    @PutMapping("/settings")
    public SettingsYamlResponse saveSettings(@RequestBody SettingsYamlRequest req) {
        if (req.yaml() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "yaml 不能为空");
        }
        String err = settingsService.validateYaml(req.yaml());
        if (err != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err);
        }
        try {
            settingsService.saveRawYaml(req.yaml());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存配置失败: " + ex.getMessage());
        }
        workspaceManager.rebuildAllAgents();
        return settings();
    }

    // ================= 记忆 =================

    @GetMapping("/memory")
    public List<PropositionEntity> memory(@RequestParam String userId) {
        return memoryService.findByUserId(userId);
    }

    @DeleteMapping("/memory/{id}")
    public void deleteMemory(@PathVariable Long id) {
        memoryService.deleteById(id);
    }
}
