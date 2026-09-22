package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SessionRegistry;
import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.middleware.FileChangeMiddleware;
import com.xinl.easyclaw.middleware.InterventionMiddleware;
import com.xinl.easyclaw.middleware.ToolFailGuard;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.scenario.McpToolExpander;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.tool.service.ToolPermissionPolicy;
import com.xinl.easyclaw.workspace.shell.SafeShellFilesystemSpec;
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

    private final AgentFactory agentFactory;
    private final SubagentLoader subagentLoader;
    private final PermissionRuleService permissionRuleService;
    private final AgentScopeProperties agentScopeProperties;
    private final ScenarioResolver scenarioResolver;
    private final McpToolExpander mcpToolExpander;
    private final SessionRegistry sessionRegistry;
    /** SPI 智能体注册表：模型偏好 / 人格 / 工具策略的声明来源 */
    private final com.xinl.easyclaw.agent.spi.AgentRegistry agentRegistry;
    /** 记忆账簿用户级设置：压缩窗 / 提取策略 / 子 Agent 提取开关 / 体积上限 */
    private final com.xinl.easyclaw.memory.settings.MemorySettingsService memorySettingsService;
    /** 框定范围的记忆提取执行器（游标窗口 + 异步开关），替代 vendored 全量同步 flush */
    private final com.xinl.easyclaw.memory.flush.ScopedMemoryFlushService scopedMemoryFlushService;
    /** 系统提示词拼装（人格 + 名册 + Skill + 场景），已抽成独立组件 */
    private final SystemPromptComposer systemPromptComposer;

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
                                 AgentScopeProperties agentScopeProperties,
                                 ScenarioResolver scenarioResolver,
                                 McpToolExpander mcpToolExpander,
                                 SessionRegistry sessionRegistry,
                                 com.xinl.easyclaw.agent.spi.AgentRegistry agentRegistry,
                                 com.xinl.easyclaw.memory.settings.MemorySettingsService memorySettingsService,
                                 com.xinl.easyclaw.memory.flush.ScopedMemoryFlushService scopedMemoryFlushService,
                                 SystemPromptComposer systemPromptComposer) {
        this.mcpToolExpander = mcpToolExpander;
        this.sessionRegistry = sessionRegistry;
        this.agentFactory = agentFactory;
        this.subagentLoader = subagentLoader;
        this.permissionRuleService = permissionRuleService;
        this.agentScopeProperties = agentScopeProperties;
        this.scenarioResolver = scenarioResolver;
        this.agentRegistry = agentRegistry;
        this.memorySettingsService = memorySettingsService;
        this.scopedMemoryFlushService = scopedMemoryFlushService;
        this.systemPromptComposer = systemPromptComposer;
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
     * 为工作区构建 HarnessAgent（主智能体入口，等价于 {@code build(workspaceId, MAIN_AGENT_ID, ...)}）。
     *
     * @param sysPromptAugment 额外注入的提示词片段（Skill / 模式），可为 null
     */
    public HarnessAgent build(String workspaceId, String name, Path workspacePath, Path easyClawDir,
                              String sysPromptAugment, String workspaceType) {
        return build(workspaceId, SubagentLoader.MAIN_AGENT_ID, name, workspacePath, easyClawDir,
                sysPromptAugment, workspaceType);
    }

    /**
     * 为工作区的<b>指定智能体</b>构建 HarnessAgent。
     * <p>
     * <b>为什么要按 agentId 分别装配</b>：在此之前一个 workspace 只有一个 HarnessAgent，
     * 「切换智能体」只是往同一个实例的 system prompt 换一段人格描述，因此
     * 「每个智能体用不同模型 / 不同工具集」根本无处落地。本重载让 agentId 成为装配维度，
     * 使 {@link com.xinl.easyclaw.base.agent.EasyClawAgent} 的声明真正生效。
     * <p>
     * <b>状态隔离（关键）</b>：{@code JsonFileAgentStateStore} 的落盘路径是
     * {@code root/<userId>/<sessionId>/<key>}，<b>不含 agent name</b>
     * （见 vendored {@code JsonFileAgentStateStore#getStatePath}）。这意味着多个智能体
     * 若共用同一个 state 根目录，在同一 sessionId 下会<b>互相覆盖对话状态且不报错</b>。
     * 因此非主智能体一律落到 {@code state/agents/<agentId>} 子目录。
     * 主智能体保持原 {@code state} 目录不变——否则存量会话状态会全部失联。
     *
     * @param agentId SPI 智能体标识（{@code main}/{@code coder}/...）；未注册时回退主智能体装配
     */
    public HarnessAgent build(String workspaceId, String agentId, String name, Path workspacePath,
                              Path easyClawDir, String sysPromptAugment) {
        return build(workspaceId, agentId, name, workspacePath, easyClawDir, sysPromptAugment, null);
    }

    /**
     * 完整装配入口。
     *
     * @param workspaceType 工作区形态（{@code WorkspaceEntity.type}，创建后不可变）；
     *                      可为 null（历史调用方未传时按场景 mode 判定）。
     *                      <b>安全边界</b>：type=ops 的工作区无论场景绑定如何，
     *                      toolkit 一律收缩为最小集——运维工作区绝不装配本地文件/代码工具，
     *                      不依赖「场景绑定 + SPI 发现」这条可能静默失效的链路。
     */
    public HarnessAgent build(String workspaceId, String agentId, String name, Path workspacePath,
                              Path easyClawDir, String sysPromptAugment, String workspaceType) {
        // 方案 C 兼容：场景绑定了已下线（SPI 未注册）的智能体标识（如历史的 creative-writer）
        // 时，统一回退主控 main 并告警。否则会装配出模型走全局默认、人格错配、
        // 且凭空多出 state/agents/<id> 隔离目录的"野"智能体。
        if (agentId != null && !SubagentLoader.MAIN_AGENT_ID.equals(agentId)
                && agentRegistry.find(agentId).isEmpty()) {
            log.warn("场景绑定的智能体 [{}] 未在 SPI 注册（可能已随角色系统下线），回退主智能体 {}",
                    agentId, SubagentLoader.MAIN_AGENT_ID);
            agentId = SubagentLoader.MAIN_AGENT_ID;
        }

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
        try {
            Files.createDirectories(globalSkillsDir);
        } catch (IOException e) {
            log.warn("创建全局能力目录失败: {}", e.getMessage());
        }

        // 场景能力绑定：一次解析，三处使用（toolkit 硬隔离 / 子 Agent skill 隔离 / 提示词推荐）
        com.xinl.easyclaw.scenario.entity.ScenarioEntity activeScenario =
                scenarioResolver.activeScenario(workspaceId);
        ScenarioBinding binding = ScenarioBinding.from(activeScenario);
        // 运维场景：能力边界由模式自声明（AgentOrchestrator.minimalToolkit，SPI 发现）——
        // toolkit 收缩为仅 remote_shell（见 createOpsToolkit）。
        // mode 依赖是 runtime scope，web/api 不静态耦合 mode 实现，经注册表查询。
        // <b>双保险</b>：工作区 type=ops（创建后不可变的硬约束）直接判定为运维形态，
        // 不依赖场景绑定与 SPI 发现——SPI 静默降级（如重启后 classpath 缺 mode 模块）时，
        // 场景 mode 判定会失效，若只依赖它，运维工作区会被装配出完整 toolkit（含本地文件工具），
        // 造成运维智能体触碰本地文件系统的事故。type 与场景判定任一命中即收缩 toolkit：
        // 宁可少给工具，不可多给。
        boolean scenarioOps = activeScenario != null
                && com.xinl.easyclaw.base.orchestration.OrchestrationModes.find(activeScenario.getMode())
                        .map(com.xinl.easyclaw.base.orchestration.AgentOrchestrator::minimalToolkit)
                        .orElse(false);
        boolean typeOps = "ops".equals(workspaceType);
        boolean opsMode = typeOps || scenarioOps;
        if (typeOps && !scenarioOps) {
            log.warn("工作区 [{}] type=ops 但场景 mode 判定未命中最小 toolkit"
                            + "（scenario={}, SPI 发现={}），已按工作区类型强制收缩 toolkit",
                    workspaceId,
                    activeScenario == null ? "无绑定" : activeScenario.getMode(),
                    activeScenario == null ? "-"
                            : com.xinl.easyclaw.base.orchestration.OrchestrationModes
                                    .find(activeScenario.getMode()).isPresent());
        }
        // 把绑定的 MCP 服务名展开成工具名，供子 Agent 工具白名单使用。
        // 必须在 loadMerged 之前完成：白名单一刀切，只有档位工具 ∪ MCP 工具并起来才完整。
        if (binding.hasToolBinding()) {
            binding = binding.withMcpTools(mcpToolExpander.expand(binding.mcpServices()));
        }
        if (!binding.isEmpty()) {
            log.info("场景能力绑定已生效: workspace={}, {}", workspaceId, binding);
        }

        // 多 Agent 编排：子 Agent 名单由 SPI 提供（.md 扫描已下线，SPI 是唯一来源）。
        // 模式自声明 subagentDispatchEnabled=false 时名册整体为空（如 ops：远程操作
        // 是单执行体串行动作）——不装配任何 SubagentDeclaration，系统提示词不含
        // 派遣说明，模型无从发起派遣。
        List<SubagentDeclaration> subagents = binding.isSubagentDispatchEnabled()
                ? subagentLoader.loadMerged(binding)
                : List.of();
        if (!binding.isSubagentDispatchEnabled()) {
            log.info("模式已声明禁止派遣子智能体: workspace={}, mode={}, 名册置空",
                    workspaceId,
                    activeScenario == null ? "-" : activeScenario.getMode());
        }

        // 角色系统下线后（方案 C），主控人格走 SPI、模型走 yml/SPI，不再解析 DB 角色。
        // 主控实例的装配 agentId 恒为 main（state 目录/会话转录与存量兼容），
        // 但「哪个智能体被选中」的实质语义——人格与模型——按绑定解析：
        // 场景 roleName 优先，其次模式主控声明（ops → ops），最后 main。
        String effectiveAgentId = resolveEffectiveAgentId(activeScenario);

        // 记忆账簿用户级设置（memory_settings 表，缺省按实体默认值落库一行）。
        // 读出的配置决定本 Agent 的压缩窗、记忆提取触发器与子 Agent 记忆 hooks 开关。
        com.xinl.easyclaw.memory.settings.MemorySettingsEntity memorySettings =
                memorySettingsService.getOrCreate();

        String sysPrompt = systemPromptComposer.compose(workspaceId, subagents, sysPromptAugment, binding);

        Model agentModel = resolveAgentModel(effectiveAgentId);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(harnessName(workspaceId, agentId))
                .description(name)
                .sysPrompt(sysPrompt)
                .model(agentModel)
                .toolkit(opsMode
                        ? agentFactory.createOpsToolkit()
                        : agentFactory.createWorkspaceToolkit(
                                binding.hasMcpBinding() ? binding.mcpServices() : null))
                // workspace 根 = .easyClaw/agent：AGENTS.md/MEMORY.md/skills/运行时数据全部集中于此
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId, !opsMode))
                .stateStore(new JsonFileAgentStateStore(stateDir(agentRoot, agentId)))
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
                // 上下文压缩绝对上限（CompactionMiddleware 第 4 参）：用户级配置，默认 48K。
                // 历史硬编码 16K——yml triggerTokens 默认 100K 永远摸不到，实际触发全由此值
                // 决定；16K 对长会话过小，每几轮压缩一次（每次压缩 = 摘要 + 提取两次额外
                // 模型调用），且模型可见近期原文过短、长会话"变笨"。
                 .maxContextTokens(memorySettings.getContextWindowTokens())
                // 横切逻辑迁移：两个 middleware 均已正式接管对应职责，
                // AgentService 中的旧实现已在同一提交内删除（file_changed 推送、
                // 工具连续失败护栏），均经 AgentEventEmitter 发 CustomEvent。
                .middleware(new FileChangeMiddleware())
                .middleware(new ToolFailGuard(sessionRegistry,
                        agentCfg.getMaxConsecutiveToolFailures()))
                // 插队（介入）注入：Builder 阶段注册，责任链上先于 build() 内自动装配的
                // vendored InboxMiddleware；drain 是消耗性的，本类取走后 vendored 版恒
                // drain 空、no-op。以 USER 消息注入当前推理步，修复 vendored 版「当前步
                // 不可见 + ASSISTANT 角色归因错误」两个缺陷。
                .middleware(new InterventionMiddleware(messageBus));
                // 压缩提示已由 vendored CompactionMiddleware 原生生命周期事件取代
                // （phase=start/end 经 AgentEventEmitter 实时推送，CustomEventTranslator 翻译），
                // 旧的 CompactionNoticeMiddleware（下一推理步才延迟到达）已删除。

        // ===== 记忆账簿策略（用户级配置生效点）=====
        // 子 Agent 默认关闭记忆 hooks（每回合提取 + 周期合并）：子的上下文防爆由
        // compaction 负责（不受影响），值得沉淀的结论经黑板归口主 Agent 统一沉淀，
        // 避免多写手提权混写主 MEMORY.md。subagentFlushEnabled=true 时与主 Agent 同策略。
        // 主 Agent 按 flushMode 装配提取触发器——默认 ALWAYS 每回合提取一次，载荷采用
        // vendored 原生的「全量上下文」语义（上下文窗已调至 100K tokens，载荷随窗有界）；
        // vendored 原版以 concatWith 把提取串进主流，曾致回合收尾被提取调用绑架挂起。
        if (shouldEnableMemoryHooks(agentId, memorySettings)) {
            // vendored flush 已由 toMemoryConfig 置 NEVER；每回合提取改由 web 侧
            // ScopedMemoryFlushMiddleware 接管（全量上下文载荷 + 同步/异步开关 + minGap
            // 节流），仅绕开 vendored「concatWith 拖住主流」的执行期缺陷，载荷仍同源全量。
            builder.memory(memorySettingsService.toMemoryConfig(memorySettings))
                    .middleware(new com.xinl.easyclaw.memory.flush.ScopedMemoryFlushMiddleware(
                            scopedMemoryFlushService, memorySettingsService, agentModel));
        } else {
            builder.disableMemoryHooks();
        }
        warnIfMemoryMdOversized(agentRoot, memorySettings);

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
        // shell 宿主走修复三重进程缺陷的 SafeShellFilesystem（管道死锁 / 超时分支顺序颠倒 / stdin 未关）
        LocalFilesystemSpec fsSpec = new SafeShellFilesystemSpec();
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
                // 压缩前的全量记忆提取关闭：它是 vendored 全量载荷模式最后的残留源
                // （实测单次 8 万+字符、与推理同步串行——重启后长会话首条消息被两个
                //  大调用绑架）。提取已由 ScopedMemoryFlush 每回合增量接管，被裁
                // 段落的信息由压缩摘要保全；offload（全文落 JSONL，无模型调用）
                // 保留，原文不丢。
                .flushBeforeCompact(false)
                .offloadBeforeCompact(true)
                .build();
    }

    /**
     * 记忆 hooks 开关判定（包私有可见性供单测）：主 Agent 恒启用记忆提取；
     * 子 Agent 仅当用户在记忆设置中开启 subagentFlushEnabled——默认关闭，
     * 子的上下文防爆由 compaction 负责，沉淀经黑板归口主 Agent 统一写记忆。
     */
    static boolean shouldEnableMemoryHooks(String agentId,
                                           com.xinl.easyclaw.memory.settings.MemorySettingsEntity settings) {
        return SubagentLoader.MAIN_AGENT_ID.equals(agentId)
                || Boolean.TRUE.equals(settings.getSubagentFlushEnabled());
    }

    /**
     * MEMORY.md 体积体检：超出用户配置上限时打 warn（含当前体积与上限），
     * 不擅自裁剪——淘汰策略（保留头部铁律还是尾部新条目）待用户拍板后另行落地。
     * 体积直接影响每次记忆提取的调用载荷（全量注入 prompt），超限不治理会形成
     * 「记忆越多 → 提取越慢 → 越容易挂起」的自增长恶性循环。
     */
    private void warnIfMemoryMdOversized(Path agentRoot,
                                         com.xinl.easyclaw.memory.settings.MemorySettingsEntity settings) {
        try {
            Path memoryMd = agentRoot.resolve("MEMORY.md");
            if (!java.nio.file.Files.exists(memoryMd)) {
                return;
            }
            long sizeKb = java.nio.file.Files.size(memoryMd) / 1024;
            if (sizeKb > settings.getMemoryMdMaxKb()) {
                log.warn("MEMORY.md 体积超限：当前 {}KB > 上限 {}KB——每次记忆提取将携带全量内容，"
                        + "建议精简或在设置中调整上限", sizeKb, settings.getMemoryMdMaxKb());
            }
        } catch (Exception e) {
            log.debug("MEMORY.md 体积体检失败（忽略）: {}", e.getMessage());
        }
    }

    // ==================== 模型与权限 ====================

    /**
     * 解析「哪个智能体被选中」的实质标识（人格与模型跟随它），优先级：
     * 场景 roleName → 模式主控声明（{@code mainAgentId()}）→ main。
     * <p>
     * <b>注意</b>：返回值只用于人格/模型解析，<b>不</b>用于装配 agentId——
     * 主控实例的装配 agentId 恒为 main（state 目录、会话转录与存量兼容）。
     * roleName 未在 SPI 注册时告警并跳过（绑定失效，回退模式主控/main），
     * 与 {@code SystemPromptComposer#resolveMainPersona} 的解析链保持一致。
     */
    private String resolveEffectiveAgentId(
            com.xinl.easyclaw.base.profile.ScenarioProfile activeScenario) {
        if (activeScenario != null) {
            String roleName = activeScenario.getRoleName();
            if (roleName != null && !roleName.isBlank()
                    && !SubagentLoader.MAIN_AGENT_ID.equals(roleName)) {
                if (agentRegistry.find(roleName).isPresent()) {
                    return roleName;
                }
                log.warn("场景绑定的智能体 [{}] 未在 SPI 注册，模型回退模式主控/main"
                                + "（检查 agent 模块是否在 classpath）",
                        roleName);
            }
            var modeMain = com.xinl.easyclaw.base.orchestration.OrchestrationModes
                    .find(activeScenario.getMode())
                    .map(com.xinl.easyclaw.base.orchestration.AgentOrchestrator::mainAgentId)
                    .filter(id -> !SubagentLoader.MAIN_AGENT_ID.equals(id))
                    .filter(id -> agentRegistry.find(id).isPresent());
            if (modeMain.isPresent()) {
                return modeMain.get();
            }
        }
        return SubagentLoader.MAIN_AGENT_ID;
    }

    /**
     * 按 agentId 解析模型：SPI 优先，application.yml 的 {@code agents.&lt;id&gt;} 配置回退，
     * 全局默认兜底。
     * <p>
     * <b>SPI 优先</b>：{@link com.xinl.easyclaw.base.agent.EasyClawAgent#modelPreference()
     * modelPreference()} 有有效声明时直接使用。
     * <p>
     * SPI 未声明时查 {@code application.yml}（{@link AgentScopeProperties#resolveAgentModel}），
     * 仍无偏好则跟随全局默认模型。角色系统下线后（方案 C），原 DB 角色模型来源已移除。
     */
    private Model resolveAgentModel(String agentId) {
        com.xinl.easyclaw.base.agent.ModelPreference pref = null;
        try {
            com.xinl.easyclaw.base.agent.EasyClawAgent spiAgent =
                    agentRegistry.find(agentId).orElse(null);
            if (spiAgent != null) {
                pref = spiAgent.modelPreference();
                if (pref != null && pref.hasPreference()) {
                    log.info("resolveAgentModel[{}]: SPI 优先 -> modelId={}, baseUrl={}",
                            agentId, pref.modelId(),
                            pref.baseUrl() != null ? "custom" : "default");
                    Model m = agentFactory.resolveModelWithCredentials(
                            pref.modelId(), pref.baseUrl(), pref.apiKey());
                    log.info("resolveAgentModel[{}]: SPI 模型 -> {}", agentId, m.getModelName());
                    return m;
                }
                log.debug("resolveAgentModel[{}]: SPI 无模型偏好，回退 yml 配置", agentId);
            } else {
                log.debug("resolveAgentModel[{}]: SPI 未注册，回退 yml 配置", agentId);
            }
        } catch (Exception e) {
            log.warn("resolveAgentModel[{}]: SPI 模型解析异常，回退 yml 配置: {}",
                    agentId, e.getMessage());
        }

        // 2. application.yml 的 agents.<id> 配置回退
        try {
            com.xinl.easyclaw.base.agent.ModelPreference ymlPref =
                    agentScopeProperties.resolveAgentModel(agentId);
            if (ymlPref != null && ymlPref.hasPreference()) {
                log.info("resolveAgentModel[{}]: 使用 yml 配置模型 -> {}",
                        agentId, ymlPref.modelId());
                return agentFactory.resolveModelWithCredentials(
                        ymlPref.modelId(), ymlPref.baseUrl(), ymlPref.apiKey());
            }
        } catch (Exception e) {
            log.warn("resolveAgentModel[{}]: yml 模型解析失败，回退全局默认: {}",
                    agentId, e.getMessage());
        }

        // 3. 全局默认兜底
        Model fallback = ModelRegistry.resolve(agentFactory.getModelId());
        log.info("resolveAgentModel[{}]: 全局默认兜底 -> {}", agentId, fallback.getModelName());
        return fallback;
    }

    /**
     * 状态存储目录：主智能体保持原目录（存量兼容），
     * 非主智能体落到 {@code state/agents/<agentId>} 子目录。
     * <p>
     * 原因：{@link io.agentscope.core.state.JsonFileAgentStateStore} 的路径是
     * {@code root/<userId>/<sessionId>/<key>}，<b>不含 agent name</b>。
     * 若多个 agent 共享同一个 root 目录，同一 sessionId 下会互相覆盖状态。
     * 主智能体保持原目录不动，确保存量会话不受影响。
     */
    static Path stateDir(Path agentRoot, String agentId) {
        if (agentId == null || SubagentLoader.MAIN_AGENT_ID.equals(agentId)) {
            return agentRoot.resolve("state");
        }
        return agentRoot.resolve("state").resolve("agents").resolve(agentId);
    }

    /**
     * HarnessAgent 内部 name：主智能体保持 workspaceId（存量兼容），
     * 非主智能体用 {@code agentId}，使其在框架状态、转录、日志中可区分。
     */
    static String harnessName(String workspaceId, String agentId) {
        if (agentId == null || SubagentLoader.MAIN_AGENT_ID.equals(agentId)) {
            return workspaceId;
        }
        return agentId;
    }

    /**
     * 智能体显示名：优先取 SPI MainAgent 的 {@code profile.displayName}，
     * 未配置或为空时回退 workspace 自身名称。
     */
    public String resolveAgentDisplayName(String fallback) {
        try {
            String displayName = agentRegistry.find(SubagentLoader.MAIN_AGENT_ID)
                    .map(a -> a.profile().displayName())
                    .filter(d -> d != null && !d.isBlank())
                    .orElse(null);
            if (displayName != null) {
                return displayName;
            }
        } catch (Exception e) {
            log.debug("读取主控 SPI displayName 失败，用 workspace name: {}", e.getMessage());
        }
        return fallback;
    }

    /**
     * 权限上下文：读工具直接放行；写/执行工具每次征求用户确认；
     * 并注入用户"永久允许"的规则（不再询问）。
     */
    private PermissionContextState buildPermissionContext(String workspaceId, boolean whitelistEnabled) {
        PermissionContextState.Builder pb = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        // 只读工具：直接放行，不打断工作流。清单来自 ToolPermissionPolicy（唯一权威来源，
        // 前端授权页面也读它 —— 避免两边各写一份后漂移）
        for (String tool : ToolPermissionPolicy.silentlyAllowed()) {
            pb.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "system"));
        }
        // 用户"永久允许"的工具：直接放行（按 workspace 隔离的持久化规则）。
        // 场景未启用白名单机制（ops）时整体跳过：不注入 user ALLOW 规则、
        // ASK 规则也不因授权而跳过 → 每次调用都弹确认
        Set<String> alwaysAllowed = whitelistEnabled
                ? permissionRuleService.alwaysAllowedTools(workspaceId)
                : Set.of();
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
