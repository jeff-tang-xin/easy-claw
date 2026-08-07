package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.filesystem.CrlfNormalizingLocalFilesystemSpec;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
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

    private final Map<String, WorkspaceContext> workspaceCache = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> sessionCache = new ConcurrentHashMap<>();

    public WorkspaceManager(WorkspaceRepository workspaceRepository,
                            SessionRepository sessionRepository,
                            AgentFactory agentFactory,
                            SubagentLoader subagentLoader,
                            PermissionRuleService permissionRuleService,
                            RoleManagementService roleService) {
        this.workspaceRepository = workspaceRepository;
        this.sessionRepository = sessionRepository;
        this.agentFactory = agentFactory;
        this.subagentLoader = subagentLoader;
        this.permissionRuleService = permissionRuleService;
        this.roleService = roleService;
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

        HarnessAgent agent = buildAgent(workspaceId, meta.getName(), workspacePath, easyClawDir);

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
        Path agentRoot = easyClawDir.resolve("agent");

        // 文件系统沙箱：ROOTED 模式 = 仅允许声明的根目录内操作；
        // project 根 = 用户指定目录（AI 文件操作的工作目录，显式允许读写），
        // 杜绝 AgentScope 默认将应用运行目录（如 F:\java\Easy-Claw）暴露给 Agent
        LocalFilesystemSpec fsSpec = new CrlfNormalizingLocalFilesystemSpec();
        fsSpec.mode(LocalFsMode.ROOTED);
        fsSpec.project(workspacePath);
        fsSpec.projectWritable(true);

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

        // 系统提示词：默认人格 + 团队模式引导（并行调度、结果汇总、动态组合）
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
                // 全局 Skills（优先级低于 workspace 级 skills，同名时 workspace 优先）
                .projectGlobalSkillsDir(globalSkillsDir)
                // 禁用 harness 自身的会话文件持久化（.easyClaw/agent/<userId>/agents/...jsonl），
                // 状态统一由 JsonFileAgentStateStore 落在 .easyClaw/agent/state
                .disableSessionPersistence()
                // 上下文自动压缩：80 条消息 或 10 万 tokens 触发，保留最近 20 条（约 3 万 tokens）。
                // 阈值调大减少 MemoryFlush 压缩询问吞回复；token 阈值防止长工具结果撑爆模型窗口
                // （曾出现 prompt 29.7 万 tokens → 模型 API 拒绝 → Retries exhausted）
                .compaction(CompactionConfig.builder()
                        .triggerMessages(80)
                        .keepMessages(20)
                        .triggerTokens(100_000)
                        .keepTokens(30_000)
                        .build())
                .maxIters(15)
                // Plan-and-Execute 模式：AI 可调用 plan_enter / plan_write / plan_exit 三个工具，
                // 在 plan mode 期间强制只读，先规划再执行，避免误删误改。
                // plan 文件默认名 PLAN.md；AgentService 每轮对话前会注入 plans/{sessionId}/ 路径
                // 实现会话隔离（不同会话的方案互不覆盖）
                .planFileDirectory("plans")
                .enablePlanMode()
                // plan mode 下允许 shell 执行（用于跑 build/test 等只读命令验证假设）
                .allowShellInPlanMode()
                // 关闭 tracing log：默认注入的 AgentTraceMiddleware 只打 SLF4J 日志不落盘，
                // 但生产环境每轮都打会刷屏，调试时再开 .enableAgentTracingLog(true)
                .enableAgentTracingLog(false)
                // 硬 token 上限：默认 8000 太保守，现代模型窗口普遍 128K，留 16K 足够容纳
                // 多轮工具调用结果而不触发硬性截断
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
            if (main != null && main.getModel() != null && !main.getModel().isBlank()) {
                return agentFactory.resolveModel(main.getModel());
            }
        } catch (Exception e) {
            log.warn("读取主智能体角色模型失败，用全局默认: {}", e.getMessage());
        }
        return ModelRegistry.resolve(agentFactory.getModelId());
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
            "write_file", "edit_file", "file_write", "file_edit",
            "file_append", "file_remove", "file_delete", "file_move", "file_copy",
            "shell", "shell_execute", "execute", "bash"
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
        workspaceCache.remove(workspaceId);
        WorkspaceContext rebuilt = restoreWorkspace(workspaceId);
        if (rebuilt != null) {
            workspaceCache.put(workspaceId, rebuilt);
            log.info("Workspace Agent 已重建: {}", workspaceId);
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
     * 按 Easy-Claw 规范初始化 Workspace 结构（Agent 相关内容严格集中）：
     * <ul>
     *   <li>{@code .easyClaw/agent/}：harness workspace 根（AGENTS.md / MEMORY.md / skills / subagents / 运行时数据）</li>
     *   <li>{@code .easyClaw/agent/state/}：Agent 状态存储根（按 userId/sessionId 持久化）</li>
     * </ul>
     * 用户指定目录（workspace 根）仅保留项目文件，零污染。
     * 旧版本 workspace 根的 AGENTS.md/MEMORY.md/skills/subagents 与旧 .agentscope 目录会自动迁移到 .easyClaw/agent。
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

            // 示例子 Agent 声明（仅首次创建，用户可修改）
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

            // AGENTS.md：Agent 人格定义（harness 从 workspace 根=.easyClaw/agent 自动加载，仅在不存在时创建）
            Path agentsFile = agentDir.resolve("AGENTS.md");
            if (!Files.exists(agentsFile)) {
                Files.writeString(agentsFile, """
                        # Agent 人格定义

                        ## 角色
                        你是当前工作区的 AI 编程助手（主控 Agent）

                        ## 目标
                        帮助用户高效完成当前工作区的编程与文件任务

                        ## 工作空间（最重要）
                        - 你的一切文件操作都限制在当前工作区内（用户指定的工作目录）
                        - 所有路径基于工作区根目录，禁止访问工作区之外的任何路径
                        - 你的配置与能力集中在 .easyClaw/agent/ 下（skills/ 操作指南、subagents/ 子 Agent 声明），由系统管理，不要修改

                        ## 子 Agent 编排
                        你有多个子 Agent 可以调度，它们与你在同一工作区内工作：
                        - reviewer：代码审查专家（检查代码质量、发现 Bug、改进建议）
                        当任务适合交给专项子 Agent 时，优先调度子 Agent 协同完成，而不是自己硬做。
                        注意：同一子 Agent 最多调度 2 次；若子 Agent 无法完成，请自己直接处理，禁止重复调度同一子 Agent。

                        ## 行为准则
                        - 保持专业、友好
                        - 主动提供帮助，不确定时主动询问
                        - 修改代码前先阅读相关文件，理解上下文
                        - 优先使用工作区内已有工具（文件读写、代码分析、网络搜索）
                        """);
            }

            // MEMORY.md：工作区长期记忆（harness 自动加载）
            Path memoryFile = agentDir.resolve("MEMORY.md");
            if (!Files.exists(memoryFile)) {
                Files.writeString(memoryFile, """
                        # 工作区记忆

                        ## 项目信息
                        （自动沉淀）

                        ## 用户偏好
                        （自动沉淀）

                        ## 历史决策
                        （自动沉淀）
                        """);
            }

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
        String legacyUser = AppConstants.LEGACY_USER_ID;
        for (Path legacy : List.of(
                workspacePath.resolve(legacyUser + "/agents"),
                workspacePath.resolve(legacyUser + "/sessions"),
                workspacePath.resolve(legacyUser))) {
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
