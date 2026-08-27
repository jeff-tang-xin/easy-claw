package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link HarnessAgent} 装配器。
 * <p>
 * 从 {@code WorkspaceManager} 抽出的第三层职责：**只管「怎么造一个 Agent」**，
 * 不碰 JPA 实体、不碰工作区缓存生命周期。原先这段 150 行的 builder 链和
 * 工作区 CRUD、Session 管理挤在同一个类里，任何一次「调模型参数」的改动
 * 都要在 48KB 的文件里翻找。
 * <p>
 * 装配内容：文件系统沙箱、消息总线、子 Agent 声明、系统提示词（人格 + 团队
 * 模式 + Skill + 场景编排）、模型、权限上下文、上下文压缩策略。
 * <p>
 * <b>无状态</b>：唯一的可变状态是 {@link #refreshedPaths}（环境变量 PATH 刷新，
 * 供 Agent 的 shell 工具用），因为它必须跨 Agent 重建存活。
 */
@Component
public class WorkspaceAgentBuilder {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAgentBuilder.class);

    private final AgentFactory agentFactory;
    private final SubagentLoader subagentLoader;
    private final PermissionRuleService permissionRuleService;
    private final RoleManagementService roleService;
    private final AgentScopeProperties agentScopeProperties;
    private final ScenarioResolver scenarioResolver;

    /**
     * workspaceId → 刷新后的 PATH 环境变量。
     * <p>
     * 用户在系统里装了新工具（如 node、python）后无需重启后端：前端触发刷新，
     * 这里记下新 PATH，Agent 重建时注入 shell 环境。必须与 Agent 实例解耦存放，
     * 否则每次 rebuildAgent 都会丢。
     */
    private final Map<String, String> refreshedPaths = new ConcurrentHashMap<>();

    public WorkspaceAgentBuilder(AgentFactory agentFactory,
                                 SubagentLoader subagentLoader,
                                 PermissionRuleService permissionRuleService,
                                 RoleManagementService roleService,
                                 AgentScopeProperties agentScopeProperties,
                                 ScenarioResolver scenarioResolver) {
        this.agentFactory = agentFactory;
        this.subagentLoader = subagentLoader;
        this.permissionRuleService = permissionRuleService;
        this.roleService = roleService;
        this.agentScopeProperties = agentScopeProperties;
        this.scenarioResolver = scenarioResolver;
    }

    // ==================== PATH 刷新 ====================

    public void setRefreshedPath(String workspaceId, String path) {
        if (path == null || path.isBlank()) {
            refreshedPaths.remove(workspaceId);
        } else {
            refreshedPaths.put(workspaceId, path);
        }
    }

    public String getRefreshedPath(String workspaceId) {
        return refreshedPaths.get(workspaceId);
    }

    // ==================== Agent 装配 ====================

    /**
     * 为工作区构建 HarnessAgent。
     *
     * @param sysPromptAugment 额外注入的提示词片段（Skill / 模式），可为 null
     */
    public HarnessAgent build(String workspaceId, String name, Path workspacePath, Path easyClawDir,
                              String sysPromptAugment) {
        Path agentRoot = easyClawDir.resolve("agent");
        AgentScopeProperties.Agent agentCfg = agentScopeProperties.getAgent();

        LocalFilesystemSpec fsSpec = buildFilesystemSpec(workspaceId, workspacePath, agentCfg);

        // 用 fsSpec 创建唯一的 agentFs 实例，同时给 builder 和 bus 用。
        // Bus 目录从 Harness 默认的 ".agentscope/bus" 收敛到 ".easyClaw/bus"，
        // 避免在 workspace 根散落 .agentscope 目录
        AbstractFilesystem agentFs = fsSpec.toFilesystem(workspacePath, null);
        WorkspaceMessageBus messageBus = new WorkspaceMessageBus(agentFs, ".easyClaw/bus");
        WorkspaceAsyncToolRegistry asyncRegistry =
                new WorkspaceAsyncToolRegistry(agentFs, ".easyClaw/bus/async-tools");

        // Transcript 存储：显式注入 rootPrefix，否则会散落在 workspace 根。
        // HarnessAgent 的默认兜底走 new ObjectStoreTranscriptStore(fs) 单参构造，
        // 其 rootPrefix 为空串，key 直接拼成 "<tenant>/<agentId>/<sessionId>/events/..."，
        // 因 agentFs 基准是 workspacePath（项目根），会在项目根生成 default/ 目录。
        // 同理于上面的 bus，这里把 transcript 收敛到 .easyClaw/agent/transcripts。
        ObjectStoreTranscriptStore transcriptStore = new ObjectStoreTranscriptStore(
                agentFs, RuntimeContext.empty(), ".easyClaw/agent/transcripts");

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

        String sysPrompt = composeSystemPrompt(workspaceId, subagents, sysPromptAugment);

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
                .compaction(buildCompactionConfig(agentCfg))
                // 工具结果淘汰：单结果超 40K 字符时写入磁盘，上下文仅留 2K 预览
                // Harness 默认 80K 才淘汰，这里收紧更早触发，省出更多上下文空间
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(70_000)
                        .previewChars(5_000)
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
                .transcriptStore(transcriptStore)
                .enablePlanMode(false)
                .enableAgentTracingLog(false)
                .maxContextTokens(16_000);

        for (SubagentDeclaration decl : subagents) {
            builder.subagent(decl);
        }

        return builder.build();
    }

    /**
     * 文件系统沙箱：ROOTED 模式 = 仅允许声明的根目录内操作；
     * project 根 = 用户指定目录（AI 文件操作的工作目录，显式允许读写），
     * 杜绝 AgentScope 默认将应用运行目录（如 F:\java\Easy-Claw）暴露给 Agent。
     */
    private LocalFilesystemSpec buildFilesystemSpec(String workspaceId, Path workspacePath,
                                                    AgentScopeProperties.Agent agentCfg) {
        LocalFilesystemSpec fsSpec = new LocalFilesystemSpec();
        fsSpec.mode(LocalFsMode.ROOTED);
        fsSpec.project(workspacePath);
        fsSpec.projectWritable(true);
        fsSpec.isolationScope(IsolationScope.GLOBAL);
        // 注意（AgentScope 2.0.2 已核实）：executeTimeoutSeconds 最终落到
        // LocalFilesystemWithShell.defaultTimeout，但该默认值实际上是「死配置」——
        // ShellExecuteTool 计算 timeout = (入参 != null ? 入参 : 30) 后，
        // 总是传一个非 null 的 Integer 给 sandbox.execute(...)，defaultTimeout 永远走不到。
        // 因此单条命令的真实上限由调用方传入的 timeout 参数决定（缺省 30s）。
        // 这里仍然配置它，等上游修复后即可自动生效。
        fsSpec.executeTimeoutSeconds(agentCfg.getShellTimeoutSeconds());
        fsSpec.maxOutputBytes(agentCfg.getMaxShellOutputBytes());
        String refreshedPath = refreshedPaths.get(workspaceId);
        if (refreshedPath != null) {
            fsSpec.env("PATH", refreshedPath);
        }
        return fsSpec;
    }

    /**
     * 上下文自动压缩（参数可在 application.yml 的 agentscope.agent.* 调整）。
     * <p>
     * 触发条件（OR 关系，任一满足即压缩）：
     * <ul>
     *   <li>消息数 ≥ triggerMessages（默认 120；工具调用一轮至少占 2 条消息，
     *       阈值太低/保留太少会让 Agent 忘记任务目标，出现"不知道自己在做什么"）</li>
     *   <li>token 数 ≥ triggerTokens（默认 100K，防长工具结果撑爆窗口）</li>
     * </ul>
     * 压缩后保留最近 keepMessages 条消息 / keepTokens，reserved 预留给模型输出。
     * 另外 PruneConfig 默认 protectTokens=40K / minimumTokens=20K，会在压缩前
     * 先把老工具结果输出裁剪到 2K 字符。
     */
    private CompactionConfig buildCompactionConfig(AgentScopeProperties.Agent agentCfg) {
        return CompactionConfig.builder()
                .triggerMessages(agentCfg.getCompactionTriggerMessages())
                .triggerTokens((int) agentCfg.getCompactionTriggerTokens())
                .keepMessages(agentCfg.getCompactionKeepMessages())
                .keepTokens((int) agentCfg.getCompactionKeepTokens())
                .reserved((int) agentCfg.getCompactionReservedTokens())
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true)
                .build();
    }

    // ==================== 系统提示词 ====================

    /**
     * 拼装系统提示词：默认人格 + 团队协作引导 + Skill/模式片段 + 场景编排。
     * <p>
     * 顺序有意义：越靠后的片段越具体，模型对靠后的指令更敏感。
     */
    private String composeSystemPrompt(String workspaceId, List<SubagentDeclaration> subagents,
                                       String sysPromptAugment) {
        String sysPrompt = agentFactory.defaultSystemPrompt();

        if (!subagents.isEmpty()) {
            sysPrompt = sysPrompt + teamModeGuide(subagents);
        }

        if (sysPromptAugment != null && !sysPromptAugment.isBlank()) {
            sysPrompt = sysPrompt + "\n\n" + sysPromptAugment;
            log.info("Skill 已注入 system prompt: {} chars, 尾部内容:\n---\n{}\n---",
                    sysPromptAugment.length(),
                    sysPromptAugment.length() > 800
                            ? sysPromptAugment.substring(0, 800) + "...(truncated)"
                            : sysPromptAugment);
        }

        // 场景（Scenario）：工作区激活的场景提示词 / 多智能体编排工作流注入 system prompt。
        // 激活关系持久化在 workspace_scenarios 表，重启后端后恢复工作区时依然生效
        String scenarioAugment = com.xinl.easyclaw.agent.orchestrator.OrchestrationPromptBuilder
                .build(scenarioResolver.activeScenario(workspaceId), subagents);
        if (scenarioAugment != null) {
            sysPrompt = sysPrompt + "\n\n" + scenarioAugment;
            log.info("场景已注入 system prompt: workspace={}, augment={} chars",
                    workspaceId, scenarioAugment.length());
        }

        return sysPrompt;
    }

    /** 子 Agent 团队名册与调度规则 */
    private String teamModeGuide(List<SubagentDeclaration> subagents) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    // ==================== 模型与权限 ====================

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
     * 主智能体显示名：优先取主角色（name=main）的 displayName，
     * 未配置或为空时回退 workspace 自身名称。
     */
    public String resolveAgentDisplayName(String fallback) {
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

    /** 只读工具：直接放行，不询问用户 */
    private static final List<String> READ_TOOLS = List.of(
            "read_file", "list_files", "list_directory", "glob_files", "grep_files",
            "search_files", "analyze_code", "format_code", "diff_code",
            "web_search", "fetch_webpage",
            "memory_search", "memory_get"
    );

    /** 写/执行工具：每次执行前需用户确认 */
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
        // 用户"永久允许"的工具：直接放行（按 workspace 隔离的持久化规则）
        Set<String> alwaysAllowed = permissionRuleService.alwaysAllowedTools(workspaceId);
        // 注意：PermissionEngine.checkPermission 的判定顺序是 deny → ask → allow，
        // ASK 规则优先于 ALLOW 命中 —— 已授权工具必须【不加】system ASK 规则，
        // 否则 ALLOW 永远轮不到判断，出现"已授权仍反复弹窗"
        for (String tool : WRITE_TOOLS) {
            if (alwaysAllowed.contains(tool)) {
                continue;
            }
            pb.addAskRule(tool, new PermissionRule(tool, null, PermissionBehavior.ASK, "system"));
        }
        for (String tool : alwaysAllowed) {
            pb.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "user"));
        }
        return pb.build();
    }
}
