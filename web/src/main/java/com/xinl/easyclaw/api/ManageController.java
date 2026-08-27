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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    public record SkillChild(String name, String description, String path, String content) {}

    public record SkillFileDto(String scope, String name, String description, String path, String content,
                               String type, List<SkillChild> children) {
    }

    public record SkillWriteRequest(String scope, String workspaceId, String name, String description,
                                    String content, String fileName, String type,
                                    List<Map<String, String>> children) {
    }

    @GetMapping("/skills")
    public List<SkillFileDto> listSkills(@RequestParam(required = false) String workspaceId) {
        List<SkillFileDto> result = new ArrayList<>();
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

    /** 收集 skill 的 scripts/*.py，以 "scripts/xxx.py" 作为展示名，便于与 md 子规则区分。 */
    private void collectScripts(Path scriptsDir, List<SkillChild> out) {
        if (!Files.isDirectory(scriptsDir)) return;
        try (var s = Files.list(scriptsDir)) {
            s.filter(Files::isRegularFile)
                    .filter(sp -> sp.getFileName().toString().endsWith(".py"))
                    .sorted(Comparator.comparing(sp -> sp.getFileName().toString()))
                    .forEach(sp -> out.add(new SkillChild(
                            "scripts/" + sp.getFileName(),
                            firstDocLine(sp),
                            sp.toAbsolutePath().toString(),
                            readAllSafe(sp))));
        } catch (IOException ignored) {
            // 列举失败不应让整个 skill 列表接口失败
        }
    }

    /** 取 Python 脚本的首行注释或 docstring 作为描述。 */
    private String firstDocLine(Path script) {
        try {
            for (String line : Files.readAllLines(script)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#!")) continue;
                if (t.startsWith("#")) return t.substring(1).trim();
                if (t.startsWith("\"\"\"") || t.startsWith("'''")) {
                    String body = t.substring(3).replace("\"\"\"", "").replace("'''", "").trim();
                    if (!body.isEmpty()) return body;
                    continue;
                }
                break;
            }
        } catch (IOException ignored) {
        }
        return "Python 脚本";
    }

    private void collectMd(Path dir, String scope, List<SkillFileDto> out) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    // 目录 skill：必须有 SKILL.md（harness 规范）
                    Path index = p.resolve("SKILL.md");
                    if (!Files.exists(index)) continue;
                    String desc = readDescription(index);
                    String content = readAllSafe(index);
                    List<SkillChild> children = new ArrayList<>();
                    try (var sub = Files.list(p)) {
                        sub.filter(sp -> sp.getFileName().toString().endsWith(".md")
                                        && !sp.getFileName().toString().equals("SKILL.md"))
                                .sorted(Comparator.comparing(sp -> sp.getFileName().toString()))
                                .forEach(sp -> children.add(new SkillChild(
                                        sp.getFileName().toString().replace(".md", ""),
                                        readDescription(sp),
                                        sp.toAbsolutePath().toString(),
                                        readAllSafe(sp)
                                )));
                    } catch (IOException ignored) {}
                    // scripts/ 下的脚本也要列出：它们是 skill 的可执行部分（run_skill_script 的目标），
                    // 不列出会让管理页显示的内容与 skill 实际能力不一致。
                    collectScripts(p.resolve("scripts"), children);
                    out.add(new SkillFileDto(scope, name, desc, p.toAbsolutePath().toString(),
                            content, "dir", children));
                } else if (name.endsWith(".md") && !"SKILL.md".equals(name)) {
                    // 单文件：subagent 都是这种（如 code-expert.md）
                    String desc = readDescription(p);
                    String content = readAllSafe(p);
                    String id = name.substring(0, name.length() - 3);
                    out.add(new SkillFileDto(scope, id, desc, p.toAbsolutePath().toString(),
                            content, "file", List.of()));
                }
            }
        } catch (IOException ignored) {}
    }

    private String readAllSafe(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
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
            String name = req.name() == null || req.name().isBlank() ? "skill" : req.name().trim();
            String safeName = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;

            Path baseDir;
            if ("workspace".equals(req.scope()) || "workspace-subagent".equals(req.scope())) {
                WorkspaceContext ws = workspaceManager.getWorkspace(req.workspaceId());
                if (ws == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作区不存在");
                }
                Path agentRoot = ws.getPath().resolve(".easyClaw/agent");
                baseDir = req.scope().contains("subagent")
                        ? agentRoot.resolve("subagents") : agentRoot.resolve("skills");
            } else {
                baseDir = req.scope().contains("subagent")
                        ? SystemHomePaths.globalSubagentsDir() : SystemHomePaths.globalSkillsDir();
            }
            Files.createDirectories(baseDir);

            String content = req.content() == null || req.content().isBlank()
                    ? "---\ndescription: " + safeName + "\n---\n"
                    : req.content();

            SkillFileDto created;
            if ("dir".equals(req.type())) {
                // 目录型：SKILL.md + 子规则
                Path skillDir = baseDir.resolve(safeName);
                Files.createDirectories(skillDir);
                Path index = skillDir.resolve("SKILL.md");
                Files.writeString(index, content, StandardCharsets.UTF_8);
                List<SkillChild> children = new ArrayList<>();
                if (req.children() != null) {
                    int idx = 0;
                    for (Map<String, String> child : req.children()) {
                        String cn = child.getOrDefault("name", "rule-" + (++idx));
                        if (!cn.endsWith(".md")) cn = cn + ".md";
                        String cc = child.getOrDefault("content", "");
                        Files.writeString(skillDir.resolve(cn), cc, StandardCharsets.UTF_8);
                        children.add(new SkillChild(cn.replace(".md", ""), "",
                                skillDir.resolve(cn).toAbsolutePath().toString(), cc));
                    }
                }
                created = new SkillFileDto(req.scope(), safeName,
                        req.description() == null ? "" : req.description(),
                        skillDir.toAbsolutePath().toString(), content, "dir", children);
            } else {
                // 单文件型（subagent 都是这种）
                Path file = baseDir.resolve(safeName + ".md");
                Files.writeString(file, content, StandardCharsets.UTF_8);
                created = new SkillFileDto(req.scope(), safeName,
                        req.description() == null ? "" : req.description(),
                        file.toAbsolutePath().toString(), content, "file", List.of());
            }

            if (req.workspaceId() != null) {
                workspaceManager.rebuildAgent(req.workspaceId());
            }
            return created;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/skills")
    public void deleteSkill(@RequestParam String path) {
        try {
            Path target = Path.of(path).normalize();
            if (Files.isDirectory(target)) {
                // 目录 skill：整个删掉（递归）
                try (var walk = Files.walk(target)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            } else if (Files.isRegularFile(target)) {
                Files.deleteIfExists(target);
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
        if (entity.getScope() == null || entity.getScope().isBlank()) {
            entity.setScope("GLOBAL");
        }
        return mcpService.create(entity);
    }

    @PutMapping("/mcp/{id}")
    public ResponseEntity<?> updateMcp(@PathVariable Long id, @RequestBody McpServiceEntity entity) {
        try {
            return ResponseEntity.ok(mcpService.update(id, entity));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    @DeleteMapping("/mcp/{id}")
    public ResponseEntity<?> deleteMcp(@PathVariable Long id) {
        try {
            mcpService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    @PostMapping("/mcp/{id}/connect")
    public McpServiceEntity connectMcp(@PathVariable Long id) {
        return mcpService.connect(id);
    }

    @PostMapping("/mcp/{id}/disconnect")
    public McpServiceEntity disconnectMcp(@PathVariable Long id) {
        return mcpService.disconnect(id);
    }

    @GetMapping("/mcp/templates")
    public List<McpServiceEntity> mcpTemplates() {
        return mcpService.findAllTemplates();
    }

    public record CopyFromTemplateRequest(String scope, String workspaceId) {}

    @PostMapping("/mcp/{id}/copy")
    public McpServiceEntity copyFromTemplate(@PathVariable Long id,
                                             @RequestBody(required = false) CopyFromTemplateRequest req) {
        String scope = (req != null && req.scope != null) ? req.scope : "GLOBAL";
        String workspaceId = req != null ? req.workspaceId : null;
        return mcpService.copyFromTemplate(id, scope, workspaceId);
    }

    @GetMapping("/mcp/{id}/tools")
    public McpToolsResponse getMcpTools(@PathVariable Long id) {
        McpServiceEntity entity = mcpService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在: " + id));
        List<String> available = parseToolNames(entity.getAvailableTools());
        List<String> enabled = mcpService.getEnabledTools(id);
        return new McpToolsResponse(available, enabled);
    }

    @PutMapping("/mcp/{id}/tools")
    public McpServiceEntity updateMcpTools(@PathVariable Long id, @RequestBody UpdateToolsRequest req) {
        return mcpService.updateEnabledTools(id, req.enabledTools);
    }

    public record McpToolsResponse(List<String> available, List<String> enabled) {
    }

    public record UpdateToolsRequest(List<String> enabledTools) {
    }

    private List<String> parseToolNames(String availableToolsJson) {
        if (availableToolsJson == null || availableToolsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> tools = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(availableToolsJson, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            return tools.stream()
                    .map(t -> (String) t.get("name"))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
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
