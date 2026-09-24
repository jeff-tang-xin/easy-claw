package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.base.agent.EasyClawAgent;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.HubSpokeClient;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import com.xinl.easyclaw.memory.settings.MemorySettingsService;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.tools.SkillScriptTools;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 管理类 API：Skills / 子 Agent、智能体、工具、MCP、设置、记忆
 */
@RestController
@RequestMapping("/api")
public class ManageController {

    private final AgentRegistry agentRegistry;
    private final ToolManagementService toolService;
    private final ToolRegistryService toolRegistryService;
    private final McpConnectionService mcpService;
    private final MemorySettingsService memorySettingsService;
    private final WorkspaceManager workspaceManager;
    private final CloudBootstrapService cloudBootstrapService;
    private final HubSpokeClient hubSpokeClient;
    private final SkillScriptTools skillScriptTools;

    public ManageController(AgentRegistry agentRegistry,
                            ToolManagementService toolService,
                            ToolRegistryService toolRegistryService,
                            McpConnectionService mcpService,
                            MemorySettingsService memorySettingsService,
                            WorkspaceManager workspaceManager,
                            CloudBootstrapService cloudBootstrapService,
                            SkillScriptTools skillScriptTools,
                            HubSpokeClient hubSpokeClient) {
        this.agentRegistry = agentRegistry;
        this.toolService = toolService;
        this.toolRegistryService = toolRegistryService;
        this.mcpService = mcpService;
        this.memorySettingsService = memorySettingsService;
        this.workspaceManager = workspaceManager;
        this.cloudBootstrapService = cloudBootstrapService;
        this.skillScriptTools = skillScriptTools;
        this.hubSpokeClient = hubSpokeClient;
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
        if (workspaceId != null) {
            WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
            if (ws != null) {
                Path agentRoot = ws.getPath().resolve(".easyClaw/agent");
                collectMd(agentRoot.resolve("skills"), "workspace", result);
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

    /**
     * 解析子文件落点：{@code .py} 归入 {@code scripts/}，其余按 md 子规则放在 skill 根。
     *
     * <p>名字来自用户输入，必须校验归属：{@code ../} 或绝对路径会写到 skill 目录之外。
     */
    static Path resolveChildPath(Path skillDir, String rawName) {
        String cn = rawName == null ? "" : rawName.trim().replace('\\', '/');
        if (cn.startsWith("scripts/")) {
            cn = cn.substring("scripts/".length());
        }
        if (cn.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "子文件名不能为空");
        }
        Path base = skillDir.toAbsolutePath().normalize();
        Path target = cn.endsWith(".py")
                ? base.resolve("scripts").resolve(cn)
                : base.resolve(cn.endsWith(".md") ? cn : cn + ".md");
        target = target.normalize();
        if (!target.startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "子文件名越界: " + rawName);
        }
        return target;
    }

    /** 展示名与 {@link #collectScripts} 保持一致：脚本带 scripts/ 前缀，md 去掉扩展名。 */
    static String displayChildName(String rawName) {
        String cn = rawName == null ? "" : rawName.trim().replace('\\', '/');
        if (cn.startsWith("scripts/")) {
            cn = cn.substring("scripts/".length());
        }
        return cn.endsWith(".py") ? "scripts/" + cn : cn.replace(".md", "");
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
                        String cc = child.getOrDefault("content", "");
                        // 脚本子文件（scripts/xxx.py）与 md 子规则走不同落点：
                        // 前者是 run_skill_script 的执行目标，必须落在 scripts/ 下才能被找到。
                        Path childPath = resolveChildPath(skillDir, cn);
                        Files.createDirectories(childPath.getParent());
                        Files.writeString(childPath, cc, StandardCharsets.UTF_8);
                        children.add(new SkillChild(displayChildName(cn), "",
                                childPath.toAbsolutePath().toString(), cc));
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

    public record RunScriptRequest(String workspaceId, String skill, String script, List<String> args) {}

    public record RunScriptResult(String output) {}

    /**
     * 在管理页试跑 skill 脚本。
     *
     * <p>直接委托 {@code run_skill_script} 工具，不另写执行路径——沙箱权限（skill 目录可读、
     * 工作区只读、{@code .easyClaw/} 拒绝、无写权限）必须与 Agent 调用时完全一致，
     * 否则页面「测试通过」不代表 Agent 真跑得起来。
     */
    @PostMapping("/skills/run-script")
    public RunScriptResult runSkillScript(@RequestBody RunScriptRequest req) {
        WorkspaceContext ws = null;
        if (req.workspaceId() != null && !req.workspaceId().isBlank()) {
            ws = workspaceManager.getWorkspace(req.workspaceId());
            if (ws == null) {
                // 不静默退化为全局查找：否则工作区 skill 会报「未找到 Skill」，
                // 用户无从判断是脚本问题还是工作区 id 问题。
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "工作区不存在: " + req.workspaceId());
            }
        }
        return new RunScriptResult(
                skillScriptTools.runSkillScript(req.skill(), req.script(), req.args(), ws));
    }

    public record SkillUpdateRequest(String path, String content, String workspaceId) {}

    /**
     * 更新已存在的 skill 声明文件内容。
     *
     * <p>只接受 {@code path}（列表接口已返回绝对路径）而不是 scope+name，避免在这里重算落点
     * 与 {@link #collectMd} 的扫描规则产生分歧。
     *
     * <p><b>路径校验</b>：必须落在受管目录内（全局 skills/subagents 或某工作区的
     * {@code .easyClaw/agent/} 下），否则拒绝——该端点接受调用方传入的绝对路径，
     * 不校验就等于任意文件写入。
     */
    @PutMapping("/skills")
    public SkillFileDto updateSkill(@RequestBody SkillUpdateRequest req) {
        if (req.path() == null || req.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        Path target = Path.of(req.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在: " + target);
        }
        if (!isManagedDeclarationPath(target, req.workspaceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "路径不在受管目录内，拒绝写入: " + target);
        }
        try {
            Files.writeString(target, req.content() == null ? "" : req.content(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "保存失败: " + e.getMessage());
        }
        // 声明文件改了必须重建 Agent，否则改动要等下次重启才生效（与 createSkill 一致）。
        if (req.workspaceId() != null && !req.workspaceId().isBlank()) {
            workspaceManager.rebuildAgent(req.workspaceId());
        }
        String content = readAllSafe(target);
        String fileName = target.getFileName().toString();
        String id = fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3) : fileName;
        return new SkillFileDto(null, id, readDescription(target),
                target.toString(), content, "file", List.of());
    }

    /**
     * 判定路径是否位于允许写入的受管目录内：全局 skills/subagents，或传入 workspaceId 对应工作区的
     * {@code .easyClaw/agent/} 子树。
     *
     * <p>与 {@link #listSkills} 的扫描范围严格对称——列表接口只扫「全局 + 传入的那一个工作区」，
     * 因此可写范围也只放开这三处，不去遍历全部工作区（能读到才可能编辑）。
     */
    private boolean isManagedDeclarationPath(Path target, String workspaceId) {
        if (target.startsWith(SystemHomePaths.globalSubagentsDir().toAbsolutePath().normalize())
                || target.startsWith(SystemHomePaths.globalSkillsDir().toAbsolutePath().normalize())) {
            return true;
        }
        if (workspaceId != null && !workspaceId.isBlank()) {
            WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
            if (ws != null) {
                Path agentRoot = ws.getPath().resolve(".easyClaw/agent")
                        .toAbsolutePath().normalize();
                return target.startsWith(agentRoot);
            }
        }
        return false;
    }

    public record SkillResetRequest(String name, String workspaceId) {}

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

    // ================= 智能体 =================

    /** 可派遣智能体的展示信息：前端场景/工作流下拉的数据源（方案 C 后取代原角色列表） */
    public record AgentDto(String agentId, String displayName, String description) {}

    /**
     * 列出可派遣的内置智能体（排除主控 main，避免把主控自己选作工作流执行节点）。
     * <p>名单与显示名均来自 SPI 注册表，是场景/工作流绑定 agentId 的权威可选项；
     * 与 {@code ScenarioService.availableSubagents} 同一口径（不含 main），
     * 但额外提供展示名与职责描述供下拉渲染。
     */
    @GetMapping("/agents")
    public List<AgentDto> agents() {
        List<AgentDto> result = new ArrayList<>();
        for (EasyClawAgent agent : agentRegistry.all()) {
            String agentId = agent.agentId();
            if (SubagentLoader.MAIN_AGENT_ID.equals(agentId)) {
                continue;
            }
            result.add(new AgentDto(agentId,
                    agent.profile().displayName(), agent.profile().description()));
        }
        return result;
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

    // ================= 云端接入状态 =================

    /**
     * 云端（hub）接入状态：开关、快照可用性、组织/模型面/权限、最近失败原因。
     * 仅回显 appkey 前缀，绝不回显完整密钥。
     */
    @GetMapping("/settings/cloud-status")
    public CloudBootstrapService.CloudStatus cloudStatus() {
        return cloudBootstrapService.status();
    }

    /**
     * 前端配置下发（S5 配置上收）：cloud 模式返回 hub 下发的菜单树/运维服务器/shell 白名单快照；
     * 本地模式 cloudMode=false 且三个数组为空，前端据此回退本地渲染。
     */
    @GetMapping("/settings/cloud-config")
    public CloudBootstrapService.CloudConfigView cloudConfig() {
        return cloudBootstrapService.cloudConfig();
    }

    /**
     * 手动刷新 bootstrap 快照并返回最新配置视图：hub 升级/配置变更后无需重启 spoke，
     * 运维或前端主动触发一次即可（读取 cloud-config 本身也会按需自动刷新，本端点为显式手段）。
     */
    @PostMapping("/settings/cloud-config/refresh")
    public CloudBootstrapService.CloudConfigView refreshCloudConfig() {
        cloudBootstrapService.refresh();
        return cloudBootstrapService.cloudConfig();
    }

    // ================= 云端项目与工作区绑定（S6-S7，hub spoke 端点代理） =================

    /** 绑定请求体：把 spoke 工作区绑定到 hub 项目（projectId 是 spoke 本地事实） */
    public record WorkspaceBindRequest(Long projectId) {}

    /** spoke 本地绑定视图：与前端 CloudBinding 结构对齐（projectName 恒 null，前端回退显示 #id） */
    public record CloudBindingView(boolean bound, Long projectId, String projectName) {}

    /**
     * hub 组织项目清单代理：透传 {@code GET /api/spoke/projects} 响应体（[{id,name,slug}]）。
     * 原样透传而非转视图：hub 侧加字段时 spoke 无需同步改代码。
     */
    @GetMapping("/manage/cloud/projects")
    public ResponseEntity<String> cloudProjects() {
        return hubProxyGet("/api/spoke/projects");
    }

    /**
     * 查询 spoke 工作区的 hub 项目绑定：读 spoke 本地 projectId（绑定是 spoke 端事实，hub 不维护）。
     * projectName 恒为 null——前端按 {@code #projectId} 回退展示，避免为展示名再依赖 hub 可用性。
     */
    @GetMapping("/manage/workspaces/{workspaceId}/cloud-binding")
    public CloudBindingView cloudBinding(@PathVariable String workspaceId) {
        Long projectId = workspaceManager.getProjectId(workspaceId);
        return new CloudBindingView(projectId != null, projectId, null);
    }

    /**
     * 绑定/换绑 spoke 工作区到 hub 项目：仅更新 spoke 本地 projectId。
     * 不向 hub 登记——同一 hub 项目可被多个 spoke 工作区绑定，hub 端不持有反向映射。
     */
    @PostMapping("/manage/workspaces/{workspaceId}/cloud-binding")
    public CloudBindingView bindCloudWorkspace(@PathVariable String workspaceId,
                                               @RequestBody WorkspaceBindRequest req) {
        if (req == null || req.projectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId 不能为空");
        }
        workspaceManager.updateProjectId(workspaceId, req.projectId());
        return new CloudBindingView(true, req.projectId(), null);
    }

    /**
     * 解除 spoke 工作区的 hub 项目绑定：仅清空 spoke 本地 projectId（hub 端无绑定可解）。
     */
    @DeleteMapping("/manage/workspaces/{workspaceId}/cloud-binding")
    public CloudBindingView unbindCloudWorkspace(@PathVariable String workspaceId) {
        workspaceManager.updateProjectId(workspaceId, null);
        return new CloudBindingView(false, null, null);
    }

    /** hub GET 代理：2xx 透传响应体；失败按 {@link #callHub} 的规则映射为 HTTP 状态 */
    private ResponseEntity<String> hubProxyGet(String pathWithQuery) {
        String body = callHub(() -> hubSpokeClient.get(pathWithQuery));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * 执行 hub 调用，把 {@link HubSpokeClient.HubCallException} 映射为 HTTP 状态抛出：
     * hub 返回了非 2xx → 透传其状态码（如 409 冲突原样给前端）；网络失败 / cloud 未配置
     * （无状态码）→ 502 Bad Gateway。错误文案随 ResponseStatusException 进响应体
     * （server.error.include-message=always）。
     */
    private <T> T callHub(HubCall<T> call) {
        try {
            return call.run();
        } catch (HubSpokeClient.HubCallException e) {
            HttpStatus status = e.statusCode() == null ? HttpStatus.BAD_GATEWAY
                    : HttpStatus.resolve(e.statusCode());
            if (status == null) {
                status = HttpStatus.BAD_GATEWAY;
            }
            throw new ResponseStatusException(status, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface HubCall<T> {
        T run();
    }

    // ================= 记忆设置 =================

    /**
     * 读取本地用户的记忆账簿设置（不存在则按默认值落库返回）。
     * 旋钮语义见 {@link MemorySettingsEntity} 字段注释。
     */
    @GetMapping("/memory/settings")
    public MemorySettingsEntity memorySettings() {
        return memorySettingsService.getOrCreate();
    }

    /**
     * 部分更新记忆设置（仅覆盖非 null 字段）；flushMode 非法值返回 400。
     * 保存后重建全部工作区 Agent——装配层在 build 时读取设置，不重建不生效。
     */
    @PutMapping("/memory/settings")
    public MemorySettingsEntity updateMemorySettings(@RequestBody MemorySettingsEntity patch) {
        MemorySettingsEntity saved;
        try {
            saved = memorySettingsService.update(MemorySettingsService.LOCAL_USER_ID, patch);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        workspaceManager.rebuildAllAgents();
        return saved;
    }
}
