package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SessionRegistry;
import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.middleware.FileChangeMiddleware;
import com.xinl.easyclaw.middleware.ToolFailGuard;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.RolePromptComposer;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.scenario.McpToolExpander;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.tool.service.ToolPermissionPolicy;
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
import io.agentscope.harness.agent.bus.MessageBus;
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

    /** 默认主角色名：场景未绑定角色时使用（AI-CLAW） */
    private static final String DEFAULT_MAIN_ROLE = "main";

    private final AgentFactory agentFactory;
    private final SubagentLoader subagentLoader;
    private final PermissionRuleService permissionRuleService;
    private final RoleManagementService roleService;
    private final AgentScopeProperties agentScopeProperties;
    private final ScenarioResolver scenarioResolver;
    private final McpToolExpander mcpToolExpander;
    private final SessionRegistry sessionRegistry;

    /**
     * workspaceId → 刷新后的 PATH 环境变量。
     * <p>
     * 用户在系统里装了新工具（如 node、python）后无需重启后端：前端触发刷新，
     * 这里记下新 PATH，Agent 重建时注入 shell 环境。必须与 Agent 实例解耦存放，
     * 否则每次 rebuildAgent 都会丢。
     */
    private final Map<String, String> refreshedPaths = new ConcurrentHashMap<>();

    /**
     * workspaceId → 该工作区 Agent 使用的 MessageBus。
     * <p>
     * 用于「用户主动介入轮次」：介入消息经 {@code MessageBus.inboxPush} 投进会话收件箱，
     * 由 Harness 已装配的 {@code InboxMiddleware} 在**下一个推理步之前**排空并作为
     * HintBlock 注入上下文——这样既不中断当前回合，又能让模型立刻看到用户的新指示。
     * <p>
     * bus 原本只是 {@link #build} 内的局部变量，Agent 重建时会换新实例，
     * 故与 refreshedPaths 同样按 workspaceId 存放，由 build 覆盖刷新。
     */
    private final Map<String, MessageBus> messageBuses = new ConcurrentHashMap<>();

    public WorkspaceAgentBuilder(AgentFactory agentFactory,
                                 SubagentLoader subagentLoader,
                                 PermissionRuleService permissionRuleService,
                                 RoleManagementService roleService,
                                 AgentScopeProperties agentScopeProperties,
                                 ScenarioResolver scenarioResolver,
                                 McpToolExpander mcpToolExpander,
                                 SessionRegistry sessionRegistry) {
        this.mcpToolExpander = mcpToolExpander;
        this.sessionRegistry = sessionRegistry;
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

    // ==================== 介入通道 ====================

    /**
     * 取该工作区 Agent 当前使用的 MessageBus，供「用户主动介入轮次」投递消息。
     *
     * @return 尚未构建过 Agent 时返回 null
     */
    public MessageBus getMessageBus(String workspaceId) {
        return workspaceId == null ? null : messageBuses.get(workspaceId);
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
        // 暴露给介入功能使用（Agent 重建时以新实例覆盖）
        messageBuses.put(workspaceId, messageBus);
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

        // 场景能力绑定：一次解析，三处使用（toolkit 硬隔离 / 子 Agent skill 隔离 / 提示词推荐）
        ScenarioBinding binding = scenarioResolver.activeBinding(workspaceId);
        // 把绑定的 MCP 服务名展开成工具名，供子 Agent 工具白名单使用。
        // 必须在 loadMerged 之前完成：白名单一刀切，只有档位工具 ∪ MCP 工具并起来才完整。
        if (binding.hasToolBinding()) {
            binding = binding.withMcpTools(mcpToolExpander.expand(binding.mcpServices()));
        }
        if (!binding.isEmpty()) {
            log.info("场景能力绑定已生效: workspace={}, {}", workspaceId, binding);
        }

        // 多 Agent 编排：全局 + Workspace 两级子 Agent（同名时 workspace 覆盖 global）
        List<SubagentDeclaration> subagents = subagentLoader.loadMerged(
                globalSubagentsDir, agentRoot.resolve("subagents"), binding);

        // 当前生效角色：场景绑定优先，否则主角色（AI-CLAW）。
        // 一次解析，两处使用（system prompt 人格 / 主模型），避免二者取到不同角色
        AgentRoleEntity activeRole = resolveActiveRole(workspaceId);

        String sysPrompt = composeSystemPrompt(workspaceId, subagents, sysPromptAugment,
                binding, activeRole);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(workspaceId)
                .description(name)
                .sysPrompt(sysPrompt)
                .model(resolveMainModel(activeRole))
                .toolkit(agentFactory.createWorkspaceToolkit(
                        binding.hasMcpBinding() ? binding.mcpServices() : null))
                // workspace 根 = .easyClaw/agent：AGENTS.md/MEMORY.md/skills/运行时数据全部集中于此
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId))
                .stateStore(new JsonFileAgentStateStore(agentRoot.resolve("state")))
                .projectGlobalSkillsDir(globalSkillsDir)
                // 禁用 harness 自带的会话文件持久化（.easyClaw/agent/<userId>/agents/...jsonl），
                // 状态统一由 JsonFileAgentStateStore 落在 .easyClaw/agent/state
                .disableSessionPersistence()
                // pending tool recovery 保持默认开启：作为上下文净化漏网时的框架级安全网。
                // （历史上曾禁用，原因是 clearStaleConfirmation 会删除 assistant 的
                //  ToolUseBlock，导致框架检测到 pending 并生成孤儿 ToolResultBlock。
                //  该删除逻辑已移除——改为在 AgentService.purgePollutedContext 里为悬空
                //  tool_call 就地补配对结果，不再删消息，孤儿的源头随之消失。）
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
                .maxContextTokens(16_000)
                // 横切逻辑迁移：两个 middleware 均已正式接管对应职责，
                // AgentService 中的旧实现已在同一提交内删除（file_changed 推送、
                // 工具连续失败护栏），均经 AgentEventEmitter 发 CustomEvent。
                .middleware(new FileChangeMiddleware())
                .middleware(new ToolFailGuard(sessionRegistry,
                        agentCfg.getMaxConsecutiveToolFailures()));

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
     * 拼装系统提示词：默认人格 + 角色人格 + 团队协作引导 + Skill/模式片段 + 场景编排。
     * <p>
     * 顺序有意义：越靠后的片段越具体，模型对靠后的指令更敏感。
     * 角色人格排在基础提示词之后、场景之前——角色定义"你是谁"（相对稳定），
     * 场景定义"当前在做什么"（更具体，应当能覆盖角色的默认倾向）。
     */
    private String composeSystemPrompt(String workspaceId, List<SubagentDeclaration> subagents,
                                       String sysPromptAugment, ScenarioBinding binding,
                                       AgentRoleEntity activeRole) {
        String sysPrompt = agentFactory.defaultSystemPrompt();

        // 角色人格：由场景绑定的角色决定，未绑定时取主角色（AI-CLAW）
        String rolePrompt = RolePromptComposer.compose(activeRole);
        if (rolePrompt != null) {
            sysPrompt = sysPrompt + "\n\n" + rolePrompt;
            log.info("角色人格已注入 system prompt: workspace={}, {} chars",
                    workspaceId, rolePrompt.length());
        }

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

        // 场景（Scenario）：环境 + 能力边界 + 方法论，三段合成一块注入。
        // 激活关系持久化在 workspace_scenarios 表，重启后端后恢复工作区时依然生效
        String scenarioAugment = com.xinl.easyclaw.agent.orchestrator.OrchestrationPromptBuilder
                .build(scenarioResolver.activeScenario(workspaceId), subagents,
                        capabilityRecommendation(binding, subagents));
        if (scenarioAugment != null) {
            sysPrompt = sysPrompt + "\n\n" + scenarioAugment;
            log.info("场景已注入 system prompt: workspace={}, augment={} chars",
                    workspaceId, scenarioAugment.length());
        }

        return sysPrompt;
    }

    /**
     * 场景能力的<b>软提示</b>：把绑定的 skill / 子 Agent 作为「优先使用」建议告知主智能体。
     * <p>
     * 为什么是软的：主智能体承担兜底职责，一旦硬禁掉未绑定能力，遇到场景没预料到的
     * 请求就会直接失能。硬隔离只施加在子 Agent（见 {@code SubagentLoader}）与
     * MCP 工具注册（见 {@code AgentFactory}）两处。
     *
     * @return 待追加的提示词片段；无可推荐内容时返回 {@code null}
     */
    private String capabilityRecommendation(ScenarioBinding binding,
                                            List<SubagentDeclaration> subagents) {
        if (binding == null || binding.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (binding.hasSkillBinding()) {
            sb.append("\n- **本场景的 Skill**：")
                    .append(String.join("、", binding.skills()))
                    .append("。这是本环境为你配备的方法资产，遇到匹配任务应先加载；")
                    .append("确有场景未覆盖的需求时才使用其他 Skill，并说明理由。");
        }
        List<String> boundAgents = availableBoundAgents(binding, subagents);
        if (!boundAgents.isEmpty()) {
            sb.append("\n- **本场景的协作成员**：")
                    .append(String.join("、", boundAgents))
                    .append("。拆解任务时优先在这些成员中分工；")
                    .append("确需成员外能力时由你自己承担，并说明理由。");
        }
        if (binding.hasMcpBinding()) {
            sb.append("\n- **本场景可用的 MCP 服务（硬性）**：")
                    .append(String.join("、", binding.mcpServices()))
                    .append("。未列出的 MCP 工具已从工具集中移除，调用必然失败，不要尝试。");
        }
        if (sb.isEmpty()) {
            return null;
        }
        return sb.toString().trim();
    }

    /**
     * 过滤出「绑定了且确实加载成功」的子 Agent 名。
     * <p>绑定里可能写着已删除或改名的子 Agent，推荐一个不存在的名字会诱导模型
     * 反复调用失败的工具，因此以实际加载结果为准。
     */
    private List<String> availableBoundAgents(ScenarioBinding binding,
                                              List<SubagentDeclaration> subagents) {
        List<String> result = new java.util.ArrayList<>();
        for (String bound : binding.subagents()) {
            boolean loaded = subagents.stream()
                    .anyMatch(d -> d.getName() != null && d.getName().equalsIgnoreCase(bound));
            if (loaded) {
                result.add(bound);
            } else {
                log.warn("场景绑定的子 Agent [{}] 未在工作区加载，已从推荐名单剔除", bound);
            }
        }
        return result;
    }

    /** 子 Agent 团队名册与调度规则 */
    private String teamModeGuide(List<SubagentDeclaration> subagents) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 🤝 子 Agent 团队协作模式\n")
                .append("你可以调度以下子 Agent 作为你的团队成员协同完成任务（通过 subagent 工具）：\n");
        for (SubagentDeclaration d : subagents) {
            sb.append("- **").append(d.getName()).append("**：").append(d.getDescription()).append("\n");
        }
        // 步数取名册里的实际最小值，而不是写死数字：SubagentLoader 会按 team 模式抬升步数，
        // 提示词里若留「默认 30」会与真实上限不符，编排者据此拆任务粒度就会判断失准。
        int minSteps = subagents.stream()
                .mapToInt(SubagentDeclaration::getSteps)
                .filter(s -> s > 0)
                .min()
                .orElse(agentScopeProperties.getAgent().getSubagentSteps());
        sb.append("""
                
                协作规则：
                1. **动态组建团队**：根据任务拆解，选择最合适的成员组合，不必一次只用一个。
                2. **一律异步派发**：派活时**必须传 `timeout_seconds=0`**，一次性把同一阶段的所有子任务全部发出，
                   立即拿到 task_id 就继续往下走。**绝不要**逐个同步等待——同步调用默认只等 30 秒，
                   子 Agent 几乎不可能在 30 秒内干完，结果是超时转后台，你却白等了 30 秒，
                   这是 team 模式变慢的首要原因。
                3. **用屏障收口，不要轮询**：同一阶段全部派发完毕后，用
                   `wait_async_results(task_ids="id1,id2,...")` 一次性等齐这批任务。
                   不要写「派一个 → 等一个 → 再派下一个」，也不要反复 `task_output` 空转轮询。
                4. **区分依赖与并行**：有输入依赖的必须分阶段串行（如 planner 产出方案 → coder 才能实现 →
                   reviewer 才能评审）；彼此不依赖的必须同阶段并行（如 coder 改后端 ∥ researcher 查资料，
                   或两个 coder 各改互不相干的模块）。**判据**：B 是否需要读 A 的产出才能开工？
                   需要则串行，不需要则同批发出。
                5. **等齐再答**：所有被调度的子 Agent 结果到齐后，才统一汇总、交叉验证、给出最终答复。
                   不要用先返回的部分结果抢先下结论——后到的结果可能推翻它，会让答复自相矛盾。
                6. **任务最小化——一个子任务只做一件事**：每个子 Agent 有独立的迭代步数上限（当前 %d 步），
                   耗尽会被强制截断、产出半成品，而半成品往往看起来像成品，最容易被误当结论汇总。
                   **拆分判据**：这个任务能不能用一句话说清「做完什么就算完」？说不清就说明还能再拆。
                   ① 一次只给一个明确目标——「查 A 并改 B 再验证 C」必须拆成三个任务或三个阶段；
                   ② 宁可多派几个小任务并行，也不要派一个大任务串行——小任务失败了只需重做一小块；
                   ③ 必须带齐上下文（相关文件路径、已知结论、验收标准），子 Agent 看不到你的对话历史，
                      省略上下文会让它把步数浪费在到处找文件上。
                7. **派活必须写清交付物——什么阶段交什么结果**：模糊的任务描述是返工的头号原因。
                   每个子任务的描述里都要包含这四段，缺一段就可能拿回没法用的东西：
                   ```
                   【目标】一句话说清做完什么算完（可验证，不是「优化一下」这种）
                   【上下文】相关文件路径 + 已确认的事实 + 明确不用再查的部分
                   【交付物】要什么形态：结论清单 / 修改后的文件 / 带行号的证据 / 方案对比表
                   【中间结论】哪些结论一得出就立刻写黑板，不要等任务结束（下游成员在等）
                   ```
                   **交付物要可验证**：要「给出文件名+行号+问题描述」，而不是「看看有没有问题」；
                   要「改完并编译通过」，而不是「改一下」。验收标准写不出来，说明任务还没想清楚。
                8. **需要时为子 Agent 指定 skill**：你的上下文里有可用 skill 目录（Available Skills）。
                   若某个子任务有对应的方法论 skill（如代码评审用 `clean-code`、重构用 `code-refactor`），
                   **在任务描述开头明确写一行**：「请先用 `load_skill_through_path(skillId="<skill-id>", path="SKILL.md")`
                   加载 <skill 名> skill，并按其标准执行」。
                   **必须给出准确的 skill-id**（从你上下文的目录里原样复制），写错名字子 Agent 会加载失败并白烧步数。
                   不确定有没有合适的 skill 就不要指定——硬塞一个不相干的 skill 只会挤占它的步数。
                9. **同一成员最多返工一轮**：同一子 Agent 因结果不达标而重派，最多再给一次机会（含首次共 2 次）；
                   仍不达标就自己接手或停下来告知用户，不要靠反复重派消耗步数。
                   **注意这条只约束「重复做同一件事」**：把不同阶段的不同任务交给同一成员（如 planner 先拆解、
                   后续再据实际进展更新计划；同一个 coder 依次实现两个模块）不算返工，可以正常派发。
                10. **成员做专项，你负责收口**：单一明确的子任务交给成员；跨子任务的协调、结论的交叉验证、
                   面向用户的最终答复由你负责，不要把「汇总所有人的结果」也外包出去。
                   计划需要随执行调整时，重新调度 planner 更新计划是正常做法，不必自己硬改。
                
                共享黑板（blackboard）——团队的公共记录本：
                并行子 Agent 之间彼此看不到对方的对话，黑板是你们唯一的共享载体。
                **每个子 Agent 的系统提示里都已写入黑板协作要求**，他们知道要读、要登记，
                你不必在任务描述里重复解释黑板怎么用，但仍应做好这三件事：
                1. **开工前先读**：派活或动手前先 `blackboard_read`，看同伴已登记的结论，避免重复劳动与结论冲突。
                2. **随手就记**：得出结论、建议或风险时立即 `blackboard_append`，不要攒到收尾才写——同伴可能正等着它。
                3. **用黑板提前解锁下游**：这是并行提速的关键。若某个子任务的产出会被下一阶段用到，
                   在任务描述里明确要求它**一得出中间结论就写黑板**，而不是憋到任务结束才随最终回复带出。
                   这样下游成员读黑板就能提前开工，无需等整个上游任务收尾。
                4. **只记结论**：写结论、建议、风险、待决问题；不要把过程日志、中间草稿、大段代码倒进去。
                5. **只增不改**：黑板是追加式的，没有删除工具，也不要试图否定或覆盖他人条目；有异议就追加一条说明理由。
                """.formatted(minSteps));
        return sb.toString();
    }

    // ==================== 模型与权限 ====================

    /**
     * 解析当前工作区生效的角色：场景绑定优先，否则回退主角色 {@code main}（AI-CLAW）。
     * <p>
     * 场景绑定的角色名若查无此人（角色被删除/改名），退回主角色而不是报错——
     * 场景配置失效不该让整个工作区起不来。
     */
    private AgentRoleEntity resolveActiveRole(String workspaceId) {
        try {
            ScenarioEntity scenario = scenarioResolver.activeScenario(workspaceId);
            if (scenario != null && scenario.getRoleName() != null
                    && !scenario.getRoleName().isBlank()) {
                String bound = scenario.getRoleName().trim();
                AgentRoleEntity role = roleService.findByName(bound).orElse(null);
                if (role != null) {
                    log.info("场景[{}] 绑定角色 -> {}", scenario.getName(), bound);
                    return role;
                }
                log.warn("场景[{}] 绑定的角色[{}] 不存在，回退主角色", scenario.getName(), bound);
            }
            return roleService.findByName(DEFAULT_MAIN_ROLE).orElse(null);
        } catch (Exception e) {
            log.warn("解析激活角色失败，回退无角色人格: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 主智能体模型：优先使用<b>当前生效角色</b>（场景绑定角色，否则 main）配置的模型，
     * 未配置或无效时回退全局默认模型。
     * <p>
     * 入参而非自行查库，是为了和 {@code composeSystemPrompt} 用的角色保持同一个——
     * 否则会出现"人格来自场景绑定角色、模型却来自 main"的错配。
     */
    private Model resolveMainModel(AgentRoleEntity role) {
        try {
            log.info("resolveMainModel: role={}, model='{}'",
                    role != null ? role.getName() : "null",
                    role != null ? role.getModel() : "null");
            if (role != null && role.getModel() != null && !role.getModel().isBlank()) {
                Model m = agentFactory.resolveRoleModel(
                        role.getModel(), role.getBaseUrl(), role.getApiKey());
                log.info("resolveMainModel: 使用角色配置的模型 -> {}", m.getModelName());
                return m;
            }
        } catch (Exception e) {
            log.warn("读取角色模型失败，用全局默认: {}", e.getMessage());
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

    /**
     * 权限上下文：读工具直接放行；写/执行工具每次征求用户确认；
     * 并注入用户"永久允许"的规则（不再询问）。
     */
    private PermissionContextState buildPermissionContext(String workspaceId) {
        PermissionContextState.Builder pb = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        // 只读工具：直接放行，不打断工作流。清单来自 ToolPermissionPolicy（唯一权威来源，
        // 前端授权页面也读它 —— 避免两边各写一份后漂移）
        for (String tool : ToolPermissionPolicy.silentlyAllowed()) {
            pb.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "system"));
        }
        // 用户"永久允许"的工具：直接放行（按 workspace 隔离的持久化规则）
        Set<String> alwaysAllowed = permissionRuleService.alwaysAllowedTools(workspaceId);
        // 注意：PermissionEngine.checkPermission 的判定顺序是 deny → ask → allow，
        // ASK 规则优先于 ALLOW 命中 —— 已授权工具必须【不加】system ASK 规则，
        // 否则 ALLOW 永远轮不到判断，出现"已授权仍反复弹窗"
        for (String tool : ToolPermissionPolicy.explicitAsk()) {
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
