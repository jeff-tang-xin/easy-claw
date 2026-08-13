package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.SystemHomePaths;

import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Workspace 管理器
 * <p>
 * 负责 Workspace 的创建、配置、销毁，以及 Session 的管理。
 * 每个 Workspace 拥有独立的 HarnessAgent 实例，装配：
 * <ul>
 *   <li>独立 Toolkit（内置工具 + 已连接 MCP 工具）</li>
 *   <li>AgentScope 状态存储（{@code .easyClaw/agent/state}，按 userId/sessionId 持久化）</li>
 *   <li>.easyClaw/agent 下的 AGENTS.md / MEMORY.md / skills / subagents（AgentScope 自动加载）</li>
 * </ul>
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final WorkspaceRepository workspaceRepository;
    private final SessionRepository sessionRepository;
    private final AgentFactory agentFactory;
    private final SubagentLoader subagentLoader;
    private final PermissionRuleService permissionRuleService;
    private final RoleManagementService roleService;
    private final com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties;

    private final Map<String, WorkspaceContext> workspaceCache = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, String> refreshedPaths = new ConcurrentHashMap<>();

    public void setRefreshedPath(String workspaceId, String path) {
        if (path != null) {
            refreshedPaths.put(workspaceId, path);
        } else {
            refreshedPaths.remove(workspaceId);
        }
    }

    public String getRefreshedPath(String workspaceId) {
        return refreshedPaths.get(workspaceId);
    }

    public WorkspaceManager(WorkspaceRepository workspaceRepository,
                            SessionRepository sessionRepository,
                            AgentFactory agentFactory,
                            SubagentLoader subagentLoader,
                            PermissionRuleService permissionRuleService,
                            RoleManagementService roleService,
                            com.xinl.easyclaw.config.AgentScopeProperties agentScopeProperties) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.agentFactory = agentFactory;
        this.subagentLoader = subagentLoader;
        this.permissionRuleService = permissionRuleService;
        this.roleService = roleService;
        this.agentScopeProperties = agentScopeProperties;
    }

    /**
     * 启动迁移：历史版本的用户 ID（default-user）统一迁移为 local，
     * 使 harness 运行时数据目录（{@code <workspace>/.easyClaw/agent/<userId>/agents}）
     * 落在 {@code .easyClaw/agent/} 下，符合目录规范。
     */
    @PostConstruct
    public void migrateLegacyUserId() {
        try {
            int n = workspaceRepository.migrateUserId(AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID);
            if (n > 0) {
                log.info("已迁移 {} 个 Workspace 的用户 ID: {} → {}", n, AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID);
            }
        } catch (Exception e) {
            log.warn("用户 ID 迁移失败（可忽略）: {}", e.getMessage());
        }
    }

    // ==================== Workspace 管理 ====================

    public WorkspaceContext createWorkspace(String userId, String name, String description, String customPath) {
        if (workspaceRepository.existsByPath(customPath)) {
            throw new WorkspaceExceptions.WorkspacePathExistsException(
                    "目录已被占用: " + customPath + "，请选择其他目录");
        }

        Path workspacePath = Paths.get(customPath);
        if (!Files.exists(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotFoundException(
                    "目录不存在: " + customPath + "，请确认路径是否正确");
        }
        if (!Files.isDirectory(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotDirectoryException(
                    "路径不是目录: " + customPath);
        }
        if (!Files.isWritable(workspacePath)) {
            throw new WorkspaceExceptions.WorkspacePathNotWritableException(
                    "目录不可写: " + customPath + "，请检查权限");
        }

        String workspaceId = generateWorkspaceId(name);

        // 按 Easy-Claw 规范初始化：AGENTS.md/MEMORY.md/skills/subagents/state 全部集中在 .easyClaw/agent
        Path easyClawDir = workspacePath.resolve(".easyClaw");
        initWorkspaceStructure(workspacePath, easyClawDir);

        HarnessAgent agent = buildAgent(workspaceId, name, workspacePath, easyClawDir);

        WorkspaceContext context = WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .name(name)
                .description(description)
                .path(workspacePath)
                .agent(agent)
                .createdAt(Instant.now())
                .lastAccessed(Instant.now())
                .build();

        workspaceCache.put(workspaceId, context);

        saveWorkspaceMetadata(context);

        log.info("Workspace 创建成功: id={}, path={}, model={}",
                workspaceId, customPath, agentFactory.getModelId());
        return context;
    }

    public WorkspaceContext getWorkspace(String workspaceId) {
        WorkspaceContext context = workspaceCache.get(workspaceId);
        if (context == null) {
            context = restoreWorkspace(workspaceId);
            if (context != null) {
                workspaceCache.put(workspaceId, context);
            }
        } else {
            // 缓存命中也补齐可能被用户删除的模板文件（AGENTS.md/MEMORY.md）
            ensureWorkspaceFiles(context.getPath().resolve(".easyClaw/agent"));
        }
        if (context != null) {
            context.setLastAccessed(Instant.now());
        }
        return context;
    }

    /**
     * 从磁盘恢复 Workspace（服务重启后自动重建 Agent，状态由 AgentStateStore 按会话恢复）
     */
    private WorkspaceContext restoreWorkspace(String workspaceId) {
        return restoreWorkspace(workspaceId, null);
    }

    private WorkspaceContext restoreWorkspace(String workspaceId, String sysPromptAugment) {
        WorkspaceEntity meta = workspaceRepository.findById(workspaceId).orElse(null);
        if (meta == null) {
            return null;
        }

        Path workspacePath = Paths.get(meta.getPath());
        if (!Files.exists(workspacePath)) {
            log.warn("Workspace 目录不存在，跳过恢复: {}", meta.getPath());
            return null;
        }

        Path easyClawDir = workspacePath.resolve(".easyClaw");
        initWorkspaceStructure(workspacePath, easyClawDir);

        HarnessAgent agent = buildAgent(workspaceId, meta.getName(), workspacePath, easyClawDir, sysPromptAugment);

        return WorkspaceContext.builder()
                .workspaceId(workspaceId)
                .userId(meta.getUserId())
                .name(meta.getName())
                .description(meta.getDescription())
                .path(workspacePath)
                .agent(agent)
                .createdAt(meta.getCreatedAt())
                .lastAccessed(Instant.now())
                .restored(true)
                .build();
    }

    /**
     * 构建 Workspace 专属的 HarnessAgent：
     * 模型（可配置 Provider）+ Toolkit（内置工具 + MCP）+ 状态存储 + skills 目录
     * + 子 Agent 编排（.easyClaw/agent/subagents/*.md）+ 上下文自动压缩。
     * <p>
     * 目录规范（严格集中）：
     * <ul>
     *   <li>harness workspace 根 = {@code <ws>/.easyClaw/agent}：AGENTS.md / MEMORY.md / skills /
     *       运行时数据（{@code <userId>/agents}）全部落在这里，workspace 根零污染</li>
     *   <li>文件系统沙箱 project 根 = 用户指定目录（读写，AI 在此工作）</li>
     *   <li>状态存储 = {@code <ws>/.easyClaw/agent/state}（按 userId/sessionId 持久化）</li>
     * </ul>
     */
    private HarnessAgent buildAgent(String workspaceId, String name, Path workspacePath, Path easyClawDir) {
        return buildAgent(workspaceId, name, workspacePath, easyClawDir, null);
    }

    private HarnessAgent buildAgent(String workspaceId, String name, Path workspacePath, Path easyClawDir,
                                    String sysPromptAugment) {
        Path agentRoot = easyClawDir.resolve("agent");

        // 文件系统沙箱：ROOTED 模式 = 仅允许声明的根目录内操作；
        // project 根 = 用户指定目录（AI 文件操作的工作目录，显式允许读写），
        // 杜绝 AgentScope 默认将应用运行目录（如 F:\java\Easy-Claw）暴露给 Agent
        LocalFilesystemSpec fsSpec = new LocalFilesystemSpec();
        fsSpec.mode(LocalFsMode.ROOTED);
        fsSpec.project(workspacePath);
        fsSpec.projectWritable(true);
        fsSpec.isolationScope(IsolationScope.GLOBAL);
        AgentScopeProperties.Agent agentCfg = agentScopeProperties.getAgent();
        fsSpec.executeTimeoutSeconds(agentCfg.getShellTimeoutSeconds());
        fsSpec.maxOutputBytes(agentCfg.getMaxShellOutputBytes());
        String refreshedPath = refreshedPaths.get(workspaceId);
        if (refreshedPath != null) {
            fsSpec.env("PATH", refreshedPath);
        }

        // 用 fsSpec 创建唯一的 agentFs 实例，同时给 builder 和 bus 用。
        // Bus 目录从 Harness 默认的 ".agentscope/bus" 收敛到 ".easyClaw/bus"，
        // 避免在 workspace 根散落 .agentscope 目录
        AbstractFilesystem agentFs = fsSpec.toFilesystem(workspacePath, null);
        WorkspaceMessageBus messageBus = new WorkspaceMessageBus(agentFs, ".easyClaw/bus");
        WorkspaceAsyncToolRegistry asyncRegistry = new WorkspaceAsyncToolRegistry(agentFs, ".easyClaw/bus/async-tools");

        // 全局共享目录（~/.easyClaw/skills、~/.easyClaw/subagents）
        Path globalSkillsDir = SystemHomePaths.globalSkillsDir();
        Path globalSubagentsDir = SystemHomePaths.globalSubagentsDir();
        try {
            Files.createDirectories(globalSkillsDir);
            Files.createDirectories(globalSubagentsDir);
        } catch (IOException e) {
            log.warn("创建全局能力目录失败: {}", e.getMessage());
        }

        // 多 Agent 编排：全局 + Workspace 两级子 Agent（同名时 workspace 覆盖 global）
        List<SubagentDeclaration> subagents = subagentLoader.loadMerged(
                globalSubagentsDir, agentRoot.resolve("subagents"));

        // 系统提示词：默认人格 + 团队模式引导
        String sysPrompt = agentFactory.defaultSystemPrompt();
        if (!subagents.isEmpty()) {
            StringBuilder sb = new StringBuilder(sysPrompt);
            sb.append("\n\n## 🤝 子 Agent 团队协作模式\n")
                    .append("你可以调度以下子 Agent 作为你的团队成员协同完成任务（通过 subagent 工具）：\n");
            for (SubagentDeclaration d : subagents) {
                sb.append("- **").append(d.getName()).append("**：").append(d.getDescription()).append("\n");
            }
            sb.append("""
                    
                    协作规则：
                    1. **动态组建团队**：根据任务拆解，选择最合适的成员组合，不必一次只用一个。
                    2. **并行调度**：多个相互独立的子任务可以同时派给不同子 Agent 并行执行（同时发起多个 subagent 调用），显著加快整体进度。
                    3. **结果汇总**：等待所有被调度的子 Agent 返回后，统一汇总它们的产出，交叉验证，再给出最终答复。
                    4. **避免重复**：同一子 Agent 最多调度 2 次；若结果不理想或无法完成，改为自己直接处理，禁止重复调度同一子 Agent。
                    5. **子 Agent 只做专项**：把单一、明确的子任务交给子 Agent；整体规划、跨子任务协调、最终答复由你负责。
                    """);
            sysPrompt = sb.toString();
        }

        if (sysPromptAugment != null && !sysPromptAugment.isBlank()) {
            sysPrompt = sysPrompt + "\n\n" + sysPromptAugment;
            log.info("Skill 已注入 system prompt: {} chars, 尾部内容:\n---\n{}\n---",
                    sysPromptAugment.length(),
                    sysPromptAugment.length() > 800
                            ? sysPromptAugment.substring(0, 800) + "...(truncated)"
                            : sysPromptAugment);
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(workspaceId)
                .description(name)
                .sysPrompt(sysPrompt)
                .model(resolveMainModel())
                .toolkit(agentFactory.createWorkspaceToolkit())
                // workspace 根 = .easyClaw/agent：AGENTS.md/MEMORY.md/skills/运行时数据全部集中于此
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId))
                .stateStore(new JsonFileAgentStateStore(agentRoot.resolve("state")))
                .projectGlobalSkillsDir(globalSkillsDir)
                // 禁用 harness 自带的会话文件持久化（.easyClaw/agent/<userId>/agents/...jsonl），
                // 状态统一由 JsonFileAgentStateStore 落在 .easyClaw/agent/state
                .disableSessionPersistence()
                // 关闭 pending tool recovery：HarnessAgent 的 maybePatchPendingToolCalls 会在
                // assistant ToolUseBlock 被 clearStaleConfirmation 移除后，
                // 检测到"pending tool calls"并自动生成孤儿 ToolResultBlock，
                // 污染上下文导致 OpenAI-compatible API 报 tool_call_id is not found。
                // 我们改用 clearStaleConfirmation + cleanupPollutedContext 组合来清理残留。
                .enablePendingToolRecovery(false)
                // ── 上下文自动压缩（平衡保守策略）──
                // 触发条件（OR 关系，任一满足即压缩）：
                //   - 消息数 ≥ 80 条（Harness 默认 50，放宽避免长对话频繁压缩）
                //   - token 数 ≥ 80K（Harness 默认 0=禁用，加上防长工具结果撑爆窗口）
                // 压缩后保留：最近 20 条消息 / 16K tokens
                //   - keepTokens 不设太大，兼容 8K~128K 各类模型窗口
                //   - reserved 20K 预留给模型输出
                // 另外：PruneConfig 默认 protectTokens=40K / minimumTokens=20K，
                // 会在压缩前先把老工具结果输出裁剪到 2K 字符，避免大工具结果撑满上下文。
                .compaction(CompactionConfig.builder()
                        .triggerMessages(80)
                        .triggerTokens(80_000)
                        .keepMessages(20)
                        .keepTokens(16_000)
                        .reserved(20_000)
                        .flushBeforeCompact(true)
                        .offloadBeforeCompact(true)
                        .build())
                // 工具结果淘汰：单结果超 40K 字符时写入磁盘，上下文仅留 2K 预览
                // Harness 默认 80K 才淘汰，这里收紧更早触发，省出更多上下文空间
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(40_000)
                        .previewChars(2_000)
                        .build())
                .maxIters(agentCfg.getMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofMinutes(agentCfg.getModelTimeoutMinutes()))
                        .maxAttempts(2)
                        .build())
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofMinutes(agentCfg.getToolTimeoutMinutes()))
                        .maxAttempts(1)
                        .build())
                .messageBus(messageBus)
                .asyncToolRegistry(asyncRegistry)
                .enablePlanMode(false)
                .enableAgentTracingLog(false)
                .maxContextTokens(16_000);

        // 注册子 Agent 声明（subagents 已在上方加载）
        for (SubagentDeclaration decl : subagents) {
            builder.subagent(decl);
        }

        return builder.build();
    }

    /**
     * 主智能体模型：优先使用"主智能体"角色（name=main）配置的模型，
     * 未配置或无效时回退全局默认模型。
     */
    private Model resolveMainModel() {
        try {
            AgentRoleEntity main = roleService.findByName("main").orElse(null);
            log.info("resolveMainModel: main role found={}, model='{}'",
                    main != null, main != null ? main.getModel() : "null");
            if (main != null && main.getModel() != null && !main.getModel().isBlank()) {
                Model m = agentFactory.resolveModel(main.getModel());
                log.info("resolveMainModel: 使用角色配置的模型 -> {}", m.getModelName());
                return m;
            }
        } catch (Exception e) {
            log.warn("读取主智能体角色模型失败，用全局默认: {}", e.getMessage());
        }
        Model fallback = ModelRegistry.resolve(agentFactory.getModelId());
        log.info("resolveMainModel: 回退全局默认 -> {}", fallback.getModelName());
        return fallback;
    }

    /**
     * 只读工具：直接放行，不询问用户
     */
    private static final List<String> READ_TOOLS = List.of(
            "read_file", "list_files", "list_directory", "glob_files", "grep_files",
            "search_files", "analyze_code", "format_code", "diff_code",
            "web_search", "fetch_webpage",
            "memory_search", "memory_get"
    );

    /**
     * 写/执行工具：每次执行前需用户确认
     */
    private static final List<String> WRITE_TOOLS = List.of(
            "write_file", "edit_file", "execute"
    );

    /**
     * 权限上下文：读工具直接放行；写/执行工具每次征求用户确认；
     * 并注入用户"永久允许"的规则（不再询问）。
     */
    private PermissionContextState buildPermissionContext(String workspaceId) {
        PermissionContextState.Builder pb = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        // 只读工具：直接放行，不打断工作流
        for (String tool : READ_TOOLS) {
            pb.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "system"));
        }
        // 写入 / 编辑 / 执行类工具：每次调用前需用户确认
        for (String tool : WRITE_TOOLS) {
            pb.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "system"));
        }
        // 用户"永久允许"的工具：直接放行（按 workspace 隔离的持久化规则）
        for (String tool : permissionRuleService.alwaysAllowedTools(workspaceId)) {
            pb.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "user"));
        }
        return pb.build();
    }

    /**
     * 重建 Workspace 的 Agent（Skill / 子 Agent 声明修改后调用，使新声明生效）
     */
    public void rebuildAgent(String workspaceId) {
        rebuildAgent(workspaceId, null);
    }

    /**
     * 重建 Workspace 的 Agent，并将额外的 prompt 片段注入到 system prompt 中
     * （用于每轮对话注入模式/Skill，避免污染用户消息）。
     */
    public void rebuildAgent(String workspaceId, String sysPromptAugment) {
        workspaceCache.remove(workspaceId);
        WorkspaceContext rebuilt = restoreWorkspace(workspaceId, sysPromptAugment);
        if (rebuilt != null) {
            workspaceCache.put(workspaceId, rebuilt);
            log.info("Workspace Agent 已重建: {}, augment={}", workspaceId,
                    sysPromptAugment == null ? "none" : sysPromptAugment.length() + " chars");
        }
    }

    /**
     * 重建所有已加载 Workspace 的 Agent（工具启用/禁用后调用，使 Toolkit 过滤立即生效）
     */
    public void rebuildAllAgents() {
        for (String id : new java.util.ArrayList<>(workspaceCache.keySet())) {
            rebuildAgent(id);
        }
        log.info("已重建 {} 个 Workspace 的 Agent", workspaceCache.size());
    }

    public void deleteWorkspace(String workspaceId) {
        WorkspaceContext context = workspaceCache.remove(workspaceId);
        if (context != null) {
            try {
                context.getAgent().close();
            } catch (Exception e) {
                log.warn("关闭 Agent 时出现异常: {}", e.getMessage());
            }
            workspaceRepository.deleteById(workspaceId);
            log.info("Workspace 已删除: {}", workspaceId);
        }
    }

    public List<WorkspaceSummary> getUserWorkspaces(String userId) {
        return workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    public PathValidationResult validatePath(String path) {
        PathValidationResult result = new PathValidationResult();

        if (workspaceRepository.existsByPath(path)) {
            result.setValid(false);
            result.setMessage("该目录已被其他 Workspace 占用");
            return result;
        }

        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            result.setValid(false);
            result.setMessage("目录不存在，请创建该目录后再试");
            return result;
        }
        if (!Files.isDirectory(p)) {
            result.setValid(false);
            result.setMessage("路径不是目录");
            return result;
        }
        if (!Files.isWritable(p)) {
            result.setValid(false);
            result.setMessage("目录不可写，请检查权限");
            return result;
        }

        result.setValid(true);
        result.setMessage("目录可用");
        return result;
    }

    // ==================== Session 管理 ====================

    public SessionContext createSession(String workspaceId, String sessionId, String title) {
        WorkspaceContext workspace = getWorkspace(workspaceId);
        if (workspace == null) {
            throw new WorkspaceExceptions.WorkspaceNotFoundException("Workspace 未找到: " + workspaceId);
        }

        SessionContext session = SessionContext.builder()
                .sessionId(sessionId)
                .workspaceId(workspaceId)
                .title(title)
                .agent(workspace.getAgent())
                .createdAt(Instant.now())
                .lastAccessed(Instant.now())
                .build();

        sessionCache.put(sessionId, session);

        SessionEntity entity = SessionEntity.builder()
                .id(sessionId)
                .workspaceId(workspaceId)
                .title(title)
                .createdAt(Instant.now())
                .build();
        sessionRepository.save(entity);

        return session;
    }

    public SessionContext getSession(String sessionId) {
        SessionContext session = sessionCache.get(sessionId);
        if (session == null) {
            SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
            if (entity != null) {
                WorkspaceContext workspace = getWorkspace(entity.getWorkspaceId());
                if (workspace != null) {
                    session = SessionContext.builder()
                            .sessionId(entity.getId())
                            .workspaceId(entity.getWorkspaceId())
                            .title(entity.getTitle())
                            .agent(workspace.getAgent())
                            .createdAt(entity.getCreatedAt())
                            .lastAccessed(Instant.now())
                            .restored(true)
                            .build();
                    sessionCache.put(sessionId, session);
                }
            }
        }
        if (session != null) {
            session.setLastAccessed(Instant.now());
        }
        return session;
    }

    // ==================== 内部方法 ====================

    private void saveWorkspaceMetadata(WorkspaceContext ctx) {
        WorkspaceEntity entity = WorkspaceEntity.builder()
                .id(ctx.getWorkspaceId())
                .userId(ctx.getUserId())
                .name(ctx.getName())
                .description(ctx.getDescription())
                .path(ctx.getPath().toString())
                .status("active")
                .createdAt(ctx.getCreatedAt())
                .lastAccessedAt(Instant.now())
                .build();
        workspaceRepository.save(entity);
    }

    private WorkspaceSummary toSummary(WorkspaceEntity entity) {
        return WorkspaceSummary.builder()
                .workspaceId(entity.getId())
                .name(entity.getName())
                .agentName(resolveAgentDisplayName(entity.getName()))
                .description(entity.getDescription())
                .path(entity.getPath())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .lastAccessed(entity.getLastAccessedAt())
                .build();
    }

    /**
     * 主智能体显示名：优先取主角色（name=main）的 displayName，
     * 未配置或为空时回退 workspace 自身名称。
     */
    private String resolveAgentDisplayName(String fallback) {
        try {
            AgentRoleEntity main = roleService.findByName("main").orElse(null);
            if (main != null && main.getDisplayName() != null && !main.getDisplayName().isBlank()) {
                return main.getDisplayName();
            }
        } catch (Exception e) {
            log.debug("读取主角色 displayName 失败，用 workspace name: {}", e.getMessage());
        }
        return fallback;
    }

    /**
     * 补齐 .easyClaw/agent 下的模板文件（仅在不存在时创建，不覆盖用户修改）。
     * 每次 getWorkspace 缓存命中时也调用，确保用户删掉后能重新生成。
     */
    private void ensureWorkspaceFiles(Path agentDir) {
        try {
            Files.createDirectories(agentDir);
            Files.createDirectories(agentDir.resolve("state"));
            Files.createDirectories(agentDir.resolve("skills"));
            Files.createDirectories(agentDir.resolve("subagents"));

            Path agentsFile = agentDir.resolve("AGENTS.md");
            if (!Files.exists(agentsFile)) {
                Files.writeString(agentsFile, """
                        # AI 编程助手工作规范

                        ## 角色
                        你是 Easy-Claw AI 编程助手，当前工作区的主控 Agent。你拥有代码编写、文件操作、网络搜索、MCP 扩展工具等能力，并可调度专项子 Agent 协同完成复杂任务。

                        ## 目标
                        帮助用户高效完成当前工作区的编程与文件任务，包括但不限于：功能开发、Bug 修复、代码重构、代码审查、项目配置、文件批量处理、资料检索与分析。

                        ## 工作空间（最重要）
                        - 你的一切文件操作都限制在当前工作区内（用户指定的项目目录）
                        - 所有路径基于工作区根目录，使用相对路径（如 src/main/...），禁止访问工作区之外的任何路径
                        - 系统目录 `.easyClaw/` 存放 Agent 配置与运行时数据，**不要修改或删除**，包括：
                          - `.easyClaw/agent/subagents/` — 子 Agent 声明文件
                          - `.easyClaw/agent/skills/` — 技能定义与操作指南
                          - `.easyClaw/agent/state/` — 会话状态存储
                        - 优先使用工作区内已有工具完成任务，避免引入不必要的外部依赖

                        ## 子 Agent 编排
                        你可以调度专项子 Agent 在同一工作区内协同工作，当前可用的子 Agent 及其职责由系统动态注入。调度原则：
                        - 当任务适合交给专项子 Agent 时（如大量代码审查、深度研究分析），优先调度子 Agent 协同完成，而不是自己硬做
                        - 调度子 Agent 时给出明确的任务目标和输出要求，而不是模糊指令
                        - 同一子 Agent 最多调度 2 次；若子 Agent 无法完成，请自己直接处理，禁止重复调度同一子 Agent
                        - 子 Agent 返回结果后，你负责整合、补充和最终交付

                        ## 行为准则
                        - **专业准确** — 回答有条理，代码可运行，不确定时坦诚说明，不编造信息
                        - **理解先行** — 修改代码前先阅读相关文件，理清上下文和依赖关系，遵循项目已有的命名风格和编码约定
                        - **小步验证** — 每次聚焦一个明确目标，修改后及时验证（编译、测试、lint），确认无副作用再继续
                        - **文件安全** — 批量操作前先列出影响范围；编辑文件保留原有缩进、换行符和编码；大文件使用分页读取
                        - **主动沟通** — 遇到错误先自行排查（读报错、看日志、搜代码），无法解决再询问用户；主动识别用户意图，在合理范围内提供额外价值
                        - **Shell 规范** — Windows 环境使用兼容语法（cmd /c），长命令注意超时，优先用内置工具快速定位
                        """);
                log.info("已生成 AGENTS.md: {}", agentsFile);
            }

            Path memoryFile = agentDir.resolve("MEMORY.md");
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, """
                        # 工作区记忆

                        > 本文件由 Agent 自动维护，用于沉淀跨会话的重要信息。
                        > 仅在发现有长期价值的内容时更新，不要记录临时或一次性信息。

                        ## 项目概览
                        - 项目类型与技术栈：
                        - 构建工具与命令：
                        - 目录结构说明：

                        ## 代码约定
                        - 命名风格：
                        - 编码规范：
                        - 特殊模式或惯用写法：

                        ## 用户偏好
                        - 语言与输出风格：
                        - 工具使用习惯：
                        - 禁忌或特殊要求：

                        ## 关键决策记录
                        | 日期 | 决策内容 | 原因/背景 |
                        |------|---------|----------|
                        |      |         |          |

                        ## 已知问题与 TODO
                        - [ ] 
                        """);
                log.info("已生成 MEMORY.md: {}", memoryFile);
            }

            Path reviewerFile = agentDir.resolve("subagents").resolve("reviewer.md");
            if (!Files.exists(reviewerFile)) {
                Files.writeString(reviewerFile, """
                        ---
                        description: 代码审查专家，负责检查代码质量、发现 Bug 与改进建议
                        steps: 8
                        tools: [read_file, list_directory, search_files]
                        ---
                        你是一名资深的代码审查专家。收到任务后：
                        1. 先阅读相关文件，理解上下文
                        2. 逐段检查代码质量、潜在 Bug、安全隐患
                        3. 给出带文件与行号的改进建议
                        4. 结论用中文，简洁清晰
                        """);
            }
        } catch (IOException e) {
            log.warn("补齐 Workspace 模板文件失败: {}", agentDir, e);
        }
    }

    /**
     * 按 Easy-Claw 规范初始化 Workspace 结构（仅首次创建或迁移时调用）：
     * 迁移旧目录 → 创建基础目录 → 补齐模板文件 → 清理遗留目录。
     */
    private void initWorkspaceStructure(Path workspacePath, Path easyClawDir) {
        try {
            // 统一管理根：.easyClaw/agent（同时是 harness 的 workspace 根）
            Path agentDir = easyClawDir.resolve("agent");

            // 迁移旧 .agentscope 目录 → .easyClaw/agent（保留对话历史）
            Path legacy = workspacePath.resolve(".agentscope");
            if (Files.exists(legacy) && !Files.exists(easyClawDir)) {
                Files.createDirectories(agentDir);
                try (var walk = Files.walk(legacy)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            if (!p.equals(legacy)) {
                                Path target = agentDir.resolve(legacy.relativize(p));
                                Files.createDirectories(target.getParent());
                                Files.move(p, target);
                            }
                        } catch (IOException ignored) {
                            // 忽略单个文件迁移失败
                        }
                    });
                }
                Files.deleteIfExists(legacy);
                log.info("已迁移旧目录 {} → {}", legacy, agentDir);
            }

            Files.createDirectories(agentDir);
            Files.createDirectories(agentDir.resolve("state"));
            Files.createDirectories(agentDir.resolve("skills"));
            Files.createDirectories(agentDir.resolve("subagents"));

            // 旧版本在 workspace 根生成的文件迁移到 .easyClaw/agent（目标已存在则不覆盖）
            migrateIfAbsent(workspacePath.resolve("AGENTS.md"), agentDir.resolve("AGENTS.md"));
            migrateIfAbsent(workspacePath.resolve("MEMORY.md"), agentDir.resolve("MEMORY.md"));
            migrateDirIfAbsent(workspacePath.resolve("skills"), agentDir.resolve("skills"));
            migrateDirIfAbsent(workspacePath.resolve("subagents"), agentDir.resolve("subagents"));

            // 补齐模板文件（AGENTS.md/MEMORY.md/reviewer.md）
            ensureWorkspaceFiles(agentDir);

            // 清理 harness 旧版在 workspace 根生成的用户目录（default-user）
            cleanupLegacyDirs(workspacePath);
        } catch (IOException e) {
            log.error("初始化 Workspace 结构失败: {}", workspacePath, e);
            throw new RuntimeException("初始化 Workspace 结构失败", e);
        }
    }

    /**
     * 迁移单个文件（目标已存在则不覆盖）
     */
    private void migrateIfAbsent(Path src, Path dst) throws IOException {
        if (Files.exists(src) && !Files.exists(dst)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            log.info("已迁移 {} → {}", src, dst);
        }
    }

    /**
     * 迁移整个目录（目标已存在则不迁移）
     */
    private void migrateDirIfAbsent(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src) && !Files.exists(dst)) {
            Files.createDirectories(dst);
            try (var walk = Files.walk(src)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (!p.equals(src)) {
                            Path target = dst.resolve(src.relativize(p));
                            Files.createDirectories(target.getParent());
                            Files.move(p, target);
                        }
                    } catch (IOException ignored) {
                        // 忽略单个文件迁移失败
                    }
                });
            }
            Files.deleteIfExists(src);
            log.info("已迁移目录 {} → {}", src, dst);
        }
    }

    /**
     * 删除 harness 旧版遗留的 <workspace>/<userId> 目录（agents/sessions 会话文件），
     * 状态已由 .easyClaw/agent/state 接管。
     * 注意：现版本 userId 为 {@link AppConstants#DEFAULT_USER_ID}（local），
     * 运行时数据落在 .easyClaw/agent/local/ 下，此处仅清理历史版本遗留的 default-user 目录。
     */
    private void cleanupLegacyDirs(Path workspacePath) {
        // 旧版（default-user）+ 本版早期（local）——Harness 会在 workspace 根创建
        // <userId>/agents、<userId>/sessions，现在统一收敛到 .easyClaw/agent/state，
        // 这俩历史目录应该删掉
        for (String userId : List.of(AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID)) {
            for (Path legacy : List.of(
                    workspacePath.resolve(userId + "/agents"),
                    workspacePath.resolve(userId + "/sessions"),
                    workspacePath.resolve(userId))) {
                if (Files.exists(legacy)) {
                    try {
                        deleteRecursively(legacy);
                        log.info("已清理 harness 遗留目录: {}", legacy);
                    } catch (IOException e) {
                        log.warn("清理遗留目录失败 {}: {}", legacy, e.getMessage());
                    }
                }
            }
        }

        // Harness 默认的 ".agentscope" bus 目录也清掉（已 override 到 .easyClaw/bus）
        Path legacyBus = workspacePath.resolve(".agentscope");
        if (Files.exists(legacyBus)) {
            try {
                deleteRecursively(legacyBus);
                log.info("已清理 harness 遗留目录: {}", legacyBus);
            } catch (IOException e) {
                log.warn("清理遗留目录失败 {}: {}", legacyBus, e.getMessage());
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    private String generateWorkspaceId(String name) {
        String timestamp = java.time.ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safeName = name.toLowerCase().replaceAll("[^a-z0-9]", "-");
        return safeName + "-" + timestamp;
    }
}
