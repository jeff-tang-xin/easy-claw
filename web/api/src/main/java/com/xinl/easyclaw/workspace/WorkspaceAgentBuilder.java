package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.AgentCapabilitySource;
import com.xinl.easyclaw.agent.SessionRegistry;
import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.base.BuiltinDbIds;
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
import io.agentscope.core.model.ModelContextWindows;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
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

    /**
     * 智能体级能力绑定来源（可选）。
     * <p>
     * <b>字段注入且允许 null</b>：本类构造参数已多达十余个，再加一位会让既有单测全部连带
     * 改造；没有 Spring 上下文时必须等价于「没人做过 agent 级绑定」，行为完全回退到改造前。
     */
    @Autowired(required = false)
    private AgentCapabilitySource capabilitySource;

    /**
     * 内置能力类型注册表（可选）：把<b>工作区绑定的平台资源</b>派生成内置能力实例装进 toolkit。
     * <p>
     * 与 {@link #capabilitySource} 同样走字段注入并容忍 null——本类构造参数已多达十余个，
     * 加一位会连带改造既有单测；没有注册表时等价于「这个工作区没绑资源」，行为完全回退到改造前。
     */
    @Autowired(required = false)
    private com.xinl.easyclaw.mcp.type.BuiltinMcpRegistry builtinMcpRegistry;

    /**
     * 读工作区资源绑定用。直连 repository 而<b>不是</b> {@code WorkspaceManager}：后者已持有
     * 本类用于重建 Agent，反向注入即成环。同样容忍 null（无上下文时等价于「没绑资源」）。
     */
    @Autowired(required = false)
    private com.xinl.easyclaw.workspace.repository.WorkspaceRepository workspaceRepository;

    /**
     * db 直注册工具集（可选，V32 定稿）：工作区绑定数据库资源后，SOLO 工具面<b>直接注册</b>
     * {@code db_query/db_dml}——db 能力不走内置 MCP 类型（那条路对 db 而言是半成品：
     * 实例管理、授权收窄、连接建立三个环节割裂）。
     */
    @Autowired(required = false)
    private com.xinl.easyclaw.tools.DbTools dbTools;

    /** 绑定数据库资源的装配期自动建连（密码取 hub 下发快照，不经模型）；容忍 null */
    @Autowired(required = false)
    private com.xinl.easyclaw.db.service.DbConnectionService dbConnectionService;

    /** hub 下发资源快照（自动建连时解析默认库与密码）；容忍 null */
    @Autowired(required = false)
    private com.xinl.easyclaw.config.CloudBootstrapService cloudBootstrapService;

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
        return build(workspaceId, agentId, name, workspacePath, easyClawDir, sysPromptAugment,
                workspaceType, null, null, null);
    }

    /**
     * 完整装配入口（team 变体）：{@code teamClient}/{@code teamContext}/{@code teamSessionId}
     * 非空时挂 teamsMode（对齐 service HarnessAgentBuildService 的 teamsMode 装配位）——
     * lead Agent 获得 team 工具（任务板/邮箱/成员管理），TeamsMiddleware 绑定 lead 会话。
     */
    public HarnessAgent build(String workspaceId, String agentId, String name, Path workspacePath,
                              Path easyClawDir, String sysPromptAugment, String workspaceType,
                              io.agentscope.harness.agent.team.TeamClient teamClient,
                              io.agentscope.harness.agent.team.TeamContext teamContext,
                              String teamSessionId) {
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
        // 专属模式（运维/数据库）判定：按 modeId 分流（V30 修正）——
        // 旧实现只问 minimalToolkit()（「是否最小集」）不问「哪个模式」，db 模式加入后
        // 会被误判进 ops 分支装配 remote_shell。现在解析出 modeId 再按值分流。
        // mode 依赖是 runtime scope，web/api 不静态耦合 mode 实现，经注册表查询。
        // <b>双保险</b>：工作区 type=ops/db（创建后不可变的硬约束）直接判定为对应形态，
        // 不依赖场景绑定与 SPI 发现——SPI 静默降级（如重启后 classpath 缺 mode 模块）时，
        // 场景 mode 判定会失效，若只依赖它，专属工作区会被装配出完整 toolkit（含本地文件工具），
        // 造成专属智能体触碰本地文件系统的事故。type 与场景判定任一命中即收缩 toolkit：
        // 宁可少给工具，不可多给。
        String activeModeId = activeScenario == null ? null
                : com.xinl.easyclaw.base.orchestration.OrchestrationModes.find(activeScenario.getMode())
                        .map(com.xinl.easyclaw.base.orchestration.AgentOrchestrator::modeId)
                        .orElse(null);
        boolean typeOps = "ops".equals(workspaceType);
        boolean typeDb = BuiltinDbIds.DB.equals(workspaceType);
        boolean opsMode = typeOps || "ops".equals(activeModeId);
        boolean dbMode = typeDb || BuiltinDbIds.DB.equals(activeModeId);
        if (typeOps && !"ops".equals(activeModeId)) {
            log.warn("工作区 [{}] type=ops 但场景 mode 判定未命中 ops"
                            + "（scenario={}），已按工作区类型强制收缩 toolkit",
                    workspaceId,
                    activeScenario == null ? "无绑定" : activeScenario.getMode());
        }
        if (typeDb && !BuiltinDbIds.DB.equals(activeModeId)) {
            log.warn("工作区 [{}] type=db 但场景 mode 判定未命中 db"
                            + "（scenario={}），已按工作区类型强制收缩 toolkit",
                    workspaceId,
                    activeScenario == null ? "无绑定" : activeScenario.getMode());
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

        // 主控 toolkit 的 MCP 服务白名单：决定「哪些 MCP 服务的工具会进入本工作区」。
        // 专属 db/ops 工作区在上面的类型分支已走最小 toolkit，压根不读这个名单——
        // 工作区硬约束天然强于页面绑定，这里无需再加判断。
        List<String> mcpAllowlist = resolveMcpAllowlist(binding, effectiveAgentId, subagents);

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
                        : dbMode
                                ? agentFactory.createDbToolkit()
                                : soloToolkit(mcpAllowlist, workspaceId))
                // workspace 根 = .easyClaw/agent：AGENTS.md/MEMORY.md/skills/运行时数据全部集中于此
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId, !(opsMode || dbMode)))
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
                .compaction(buildCompactionConfig(agentCfg, agentModel))
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
                // workspace 记忆注入预算（WorkspaceContextMiddleware）：MEMORY.md 内容超出
                // 此预算时按 token 裁剪注入，避免记忆段撑爆系统提示。用户级配置，默认 48K。
                // ⚠ 注意：此值与对话压缩（compaction）触发无关——压缩触发由上方
                //   buildCompactionConfig 的 triggerMessages/triggerTokens 决定。历史注释
                //   曾误写为「CompactionMiddleware 第 4 参」，实为 WorkspaceContextMiddleware
                //   构造参，已修正，避免后人误以为调它能改变压缩频率。
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

        // team 变体：挂 TeamsMiddleware + 注册角色裁剪的 team 工具（对齐 service 的
        // teamsMode 装配位——middleware 链上、toolkit 注册由 build 内部完成）。
        if (teamClient != null && teamContext != null) {
            builder.teamsMode(teamClient, teamContext, teamSessionId);
        }

        // 运维 P2（pull 形态）：仅追加静态「终端工具能力提示」，引导模型按需主动读取；
        // 终端内容不注入对话（push 形态已废弃），工具返回经 TerminalOutputSanitizer 脱敏
        if (opsMode) {
            builder.middleware(
                    new com.xinl.easyclaw.ops.middleware.TerminalToolHintMiddleware());
        }

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
     * 构建 team 成员 Agent（轻量装配，对齐 service 的成员会话模型：独立实例+独立会话）。
     *
     * <p><b>与 lead 的差异</b>：成员不走场景绑定装配（无子 Agent 名册、无编排提示词、
     * 无 MCP 白名单展开）——成员是专职执行角色，协作面就是 team 工具（teamsMode 自动
     * 注册），生产力面由声明决定（后续按需扩展）。提示词 = 成员声明 prompt + 成员
     * 协作规则（任务板/邮箱用法），不注入 lead 的编排规则。
     *
     * <p><b>状态隔离</b>：stateStore 落 {@code state/members/<name>}，与主 Agent 及
     * 其他成员互不覆盖；transcript 同理独立目录。
     */
    public HarnessAgent buildMember(String workspaceId, SubagentDeclaration decl,
                                    Path workspacePath, Path easyClawDir,
                                    io.agentscope.harness.agent.team.TeamClient teamClient,
                                    io.agentscope.harness.agent.team.TeamContext teamContext,
                                    String memberSessionId) {
        Path agentRoot = easyClawDir.resolve("agent");
        AgentScopeProperties.Agent agentCfg = agentScopeProperties.getAgent();
        LocalFilesystemSpec fsSpec = buildFilesystemSpec(workspaceId, workspacePath, agentCfg);
        AbstractFilesystem agentFs = fsSpec.toFilesystem(workspacePath, null);
        WorkspaceMessageBus messageBus = new WorkspaceMessageBus(agentFs, ".easyClaw/bus");
        ObjectStoreTranscriptStore transcriptStore = new ObjectStoreTranscriptStore(
                agentFs, RuntimeContext.empty(), ".easyClaw/agent/transcripts/members");

        String memberName = decl.getName();
        // 成员模型：跟主 Agent 同模型（声明级 model 覆盖留待按需扩展——
        // decl.getModel() 是模型名字符串，走 resolveModelWithCredentials 需要凭据解析链，
        // 成员先复用主模型保证可用性）
        Model agentModel = resolveAgentModel(SubagentLoader.MAIN_AGENT_ID);
        String memberPrompt = """
                你是团队「%s」的成员 %s：%s

                ## 协作规则（team 工具）
                1. 用 `team` 工具协作：`listTasks`/`listClaimableTasks` 看任务板，`claimTask` 领任务，
                   `completeTask`（带结果摘要）或 `failTask`（带原因）回写，`sendMessage` 与 lead/同伴沟通，
                   方案需要 lead 拍板时 `submitPlan` 提交审批。
                2. 被唤醒（收到 notice）时先 `listMessages` 查邮箱、`listTasks` 查任务板，再决定动作；
                   没有可做的工作就直接结束回合，不要空转。
                3. 任务完成以 `completeTask` 回写为准——只说不写，lead 永远看不到结果。
                4. 你是专项执行者：把分配给你的事做完做对，不替 lead 做编排决策。
                """.formatted(
                teamContext != null ? teamContext.teamName() : "",
                memberName,
                decl.getDescription() == null ? "" : decl.getDescription());

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("member-" + memberName)
                .description("team 成员 " + memberName)
                .sysPrompt(memberPrompt)
                .model(agentModel)
                // 成员最小工具面：协作走 team 工具（teamsMode 注册），检索工具兜底查资料；
                // 不装配文件/代码工具——成员的产出以任务板 result 文本回写，批 4 按声明扩展。
                .toolkit(agentFactory.createSearchToolkit())
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId, false))
                .stateStore(new JsonFileAgentStateStore(
                        stateDir(agentRoot, "member-" + memberName)))
                .disableSessionPersistence()
                .compaction(buildCompactionConfig(agentCfg, agentModel))
                .maxIters(decl.getSteps() > 0 ? decl.getSteps() : agentCfg.getMaxIters())
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofMinutes(agentCfg.getModelTimeoutMinutes()))
                        .maxAttempts(2)
                        .build())
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofMinutes(agentCfg.getToolTimeoutMinutes()))
                        .maxAttempts(1)
                        .build())
                .messageBus(messageBus)
                .transcriptStore(transcriptStore)
                .enablePlanMode(false)
                .enableAgentTracingLog(false)
                .disableMemoryHooks();
        if (teamClient != null && teamContext != null) {
            builder.teamsMode(teamClient, teamContext, memberSessionId);
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

    /** 窗口感知触发阈值下限：低于它一律按此值。128K 窗口模型的安全触发点（历史已验证）。 */
    private static final int MIN_TRIGGER_TOKENS = 100_000;

    /** 窗口感知触发阈值上限：防止 1M+ 窗口模型把对话撑到不健康的长度。 */
    private static final int MAX_TRIGGER_TOKENS = 300_000;

    /** 窗口感知触发预留：系统提示（AGENTS.md/MEMORY.md/skills/工具定义）+ 输出预算，
     *  窗口减去它才是「对话部分的触发点」。估算本身偏高（TokenCounterUtil 2.5 字符/token），
     *  预留不足会让 200K 模型在满窗边缘触发，故取 50K 留足余量。 */
    private static final int WINDOW_RESERVED_TOKENS = 50_000;

    /**
     * 上下文自动压缩（参数可在 application.yml 的 agentscope.agent.* 调整）。
     * <p>
     * 触发条件（OR 关系，任一满足即压缩）：
     * <ul>
     *   <li>消息数 ≥ triggerMessages（默认 300；工具调用一轮至少占 2 条消息，并行时更多，
     *       阈值太低会让 Agent 忘记任务目标，出现"不知道自己在做什么"）</li>
     *   <li>token 数 ≥ triggerTokens（默认 0 = 按模型窗口自动计算：窗口 - 50K 预留，
     *       夹在 [100K, 300K]；查不到模型窗口（cloud 别名/未收录模型）回落 100K 兜底，
     *       防长工具结果撑爆窗口）</li>
     * </ul>
     * 压缩后保留最近 keepMessages 条消息 / keepTokens（默认 40K），reserved 预留给模型输出。
     * 另外 PruneConfig 默认 protectTokens=40K / minimumTokens=20K，会在压缩前
     * 先把老工具结果输出裁剪到 2K 字符。
     */
    private CompactionConfig buildCompactionConfig(AgentScopeProperties.Agent agentCfg, Model agentModel) {
        long configured = agentCfg.getCompactionTriggerTokens();
        int triggerTokens = configured > 0
                ? (int) configured
                : resolveWindowAwareTriggerTokens(agentModel);
        log.info("Compaction config: triggerMessages={}, triggerTokens={} (configured={}, model={}), keepTokens={}",
                agentCfg.getCompactionTriggerMessages(), triggerTokens, configured,
                agentModel != null ? agentModel.getModelName() : "?",
                agentCfg.getCompactionKeepTokens());
        return CompactionConfig.builder()
                .triggerMessages(agentCfg.getCompactionTriggerMessages())
                .triggerTokens(triggerTokens)
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

    /** 按模型 context window 计算压缩触发阈值（包私有可见性供单测）：{@code clamp(窗口 - 50K 预留, 100K, 300K)}。
     * 模型名来自 {@link Model#getModelName()}（纯模型名，或带 {@code provider:} 前缀），
     * 用 {@link ModelContextWindows} 内置窗口表匹配；查不到（cloud 别名、未收录模型）
     * 返回 0，由调用方回落配置值（默认 0 → 100K 兜底）。
     */
    static int resolveWindowAwareTriggerTokens(Model model) {
        if (model == null) {
            return 0;
        }
        String modelName = model.getModelName();
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        int window = lookupContextWindow(modelName);
        if (window <= 0) {
            log.debug("Compaction triggerTokens: 模型 {} 未收录窗口，回落配置值", modelName);
            return 0;
        }
        int computed = window - WINDOW_RESERVED_TOKENS;
        return Math.max(MIN_TRIGGER_TOKENS, Math.min(MAX_TRIGGER_TOKENS, computed));
    }

    /** 在 ModelContextWindows 各 provider 表中按模型名前缀匹配窗口（包私有可见性供单测；表间前缀互不重叠，取首个命中）。 */
    static int lookupContextWindow(String modelName) {
        int idx = modelName.indexOf(':');
        String bare = (idx > 0 ? modelName.substring(idx + 1) : modelName).trim();
        for (Map<String, Integer> table : List.of(
                ModelContextWindows.DASHSCOPE,
                ModelContextWindows.OPENAI,
                ModelContextWindows.DEEPSEEK,
                ModelContextWindows.GLM,
                ModelContextWindows.MINIMAX,
                ModelContextWindows.KIMI,
                ModelContextWindows.ANTHROPIC,
                ModelContextWindows.GEMINI)) {
            int window = ModelContextWindows.lookup(bare, table);
            if (window > 0) {
                return window;
            }
        }
        return 0;
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
    /**
     * 计算主控 toolkit 的 MCP 服务白名单。
     * <p>
     * <b>优先级：场景绑定 &gt; agent 级绑定 &gt; 不限制</b>。
     * <ul>
     *   <li>场景显式绑了 MCP：只放行场景内的服务。agent 绑定越界（选了场景外的服务）在此
     *       丢弃并告警——页面绑定只能收窄，不能绕过场景给出的运行时边界。</li>
     *   <li>场景没绑：用主控与各子 Agent 绑定的<b>并集</b>。子 Agent 继承主控 toolkit，
     *       父侧不注册，子侧的绑定就永远是空壳。</li>
     *   <li>两者皆空：返回 {@code null} = 不限制，保持既有行为（零回退）。</li>
     * </ul>
     */
    private List<String> resolveMcpAllowlist(ScenarioBinding binding, String effectiveAgentId,
                                             List<SubagentDeclaration> subagents) {
        if (binding.hasMcpBinding()) {
            List<String> agentBound = agentBoundServices(effectiveAgentId, subagents);
            List<String> outside = agentBound.stream()
                    .filter(name -> !containsIgnoreCase(binding.mcpServices(), name))
                    .toList();
            if (!outside.isEmpty()) {
                log.warn("agent 级绑定的 MCP 服务 {} 不在场景绑定 {} 范围内，已忽略"
                                + "（agent 绑定只能收窄，不能越过场景边界）",
                        outside, binding.mcpServices());
            }
            return binding.mcpServices();
        }
        List<String> agentBound = agentBoundServices(effectiveAgentId, subagents);
        if (!agentBound.isEmpty()) {
            log.info("MCP 服务白名单取自 agent 级绑定（场景未绑定 MCP）: {}", agentBound);
        }
        return agentBound.isEmpty() ? null : agentBound;
    }

    /** 主控 + 各子 Agent 的 agent 级 MCP 绑定并集；无来源时为空（等价于改造前行为） */
    private List<String> agentBoundServices(String effectiveAgentId, List<SubagentDeclaration> subagents) {
        if (capabilitySource == null) {
            return List.of();
        }
        Set<String> agentIds = new LinkedHashSet<>();
        agentIds.add(SubagentLoader.MAIN_AGENT_ID);
        if (effectiveAgentId != null && !effectiveAgentId.isBlank()) {
            agentIds.add(effectiveAgentId);
        }
        if (subagents != null) {
            subagents.forEach(decl -> {
                if (decl != null && decl.getName() != null && !decl.getName().isBlank()) {
                    agentIds.add(decl.getName());
                }
            });
        }
        try {
            return capabilitySource.mcpServiceNamesFor(agentIds);
        } catch (Exception e) {
            log.warn("读取 agent 级 MCP 绑定失败，按无绑定处理（不因此收紧工具集）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * SOLO / TEAM 工作区的主 toolkit：通用装配 + <b>工作区资源绑定派生的内置能力实例</b>。
     * <p>
     * 专属 db / ops 工作区不走这里——它们在类型分支已取最小 toolkit，那套硬约束是安全底线，
     * 不受 agent 页面绑定与工作区资源绑定的影响（绑定只能收窄，不能放宽）。
     */
    private io.agentscope.core.tool.Toolkit soloToolkit(List<String> mcpAllowlist, String workspaceId) {
        io.agentscope.core.tool.Toolkit toolkit = agentFactory.createWorkspaceToolkit(mcpAllowlist);
        bindWorkspaceResources(toolkit, workspaceId);
        return toolkit;
    }

    /**
     * 把工作区绑定的平台资源装进 toolkit：数据库 → 直注册 DbTools（V32 定稿，不走 MCP）；
     * 运维服务器 → 派生内置能力实例（ops 仍走 MCP 机制）。
     */
    private void bindWorkspaceResources(io.agentscope.core.tool.Toolkit toolkit, String workspaceId) {
        if (workspaceRepository == null) {
            return;
        }
        com.xinl.easyclaw.workspace.entity.WorkspaceEntity entity =
                workspaceRepository.findById(workspaceId).orElse(null);
        if (entity == null) {
            return;
        }
        registerBoundResources(toolkit, builtinMcpRegistry, workspaceId,
                entity.getDbServerKey(), entity.getOpsServerKey(), dbTools);
        ensureDbConnection(workspaceId, entity.getDbServerKey());
    }

    /**
     * 绑定数据库资源的装配期自动建连：密码取 hub 下发快照（仅服务端持有，不经模型），
     * 与 ops 内置能力的自主建连同款语义。失败只告警不阻断装配——工具仍在，AI 调用时报未连接。
     * {@code DbConnectionService.connect} 对同 (workspaceId, connKey) 幂等复用，Agent 重建不会重复建连。
     */
    private void ensureDbConnection(String workspaceId, String serverKey) {
        if (serverKey == null || serverKey.isBlank()
                || dbConnectionService == null || cloudBootstrapService == null) {
            return;
        }
        try {
            com.xinl.easyclaw.config.SpokeDbConnectionView cfg =
                    cloudBootstrapService.findDbConnection(serverKey.trim()).orElse(null);
            if (cfg == null) {
                log.warn("工作区 {} 绑定的数据库 {} 不在平台下发清单中，跳过自动建连", workspaceId, serverKey);
                return;
            }
            dbConnectionService.connect(workspaceId, serverKey.trim(), cfg.databaseName(), null, cfg.password());
            log.info("工作区 {} 绑定的数据库 {} 已自动建连（默认库 {}）",
                    workspaceId, serverKey, cfg.databaseName());
        } catch (Exception ex) {
            log.warn("工作区 {} 绑定数据库 {} 自动建连失败（库不可达或授权变化；AI 调用 db 工具时会报未连接）: {}",
                    workspaceId, serverKey, ex.getMessage());
        }
    }

    /**
     * 把工作区绑定的资源装进 toolkit。
     * <p>
     * <b>静态且参数显式</b>：本类构造参数已十余个，实例方法在单测里几乎起不来；而「绑定了就该
     * 有工具、没绑定就不该有、与场景实例同名要让位」这三条恰恰是最该钉住的行为，不该只能靠人工
     * 联调来保证。
     *
     * @param dbTools db 直注册工具集（可为 null——无 Spring 上下文时 db 绑定不生效）
     * @return 实际注册进 toolkit 的工具名（未绑定与让位的都不在其中）
     */
    static List<String> registerBoundResources(io.agentscope.core.tool.Toolkit toolkit,
                                               com.xinl.easyclaw.mcp.type.BuiltinMcpRegistry registry,
                                               String workspaceId,
                                               String dbServerKey, String opsServerKey,
                                               com.xinl.easyclaw.tools.DbTools dbTools) {
        List<String> registered = new java.util.ArrayList<>();
        registered.addAll(registerDbBinding(toolkit, workspaceId, dbServerKey, dbTools));
        if (registry != null) {
            registered.addAll(registerBoundResource(toolkit, registry, workspaceId, "ops",
                    WorkspaceResourceService.KIND_OPS, opsServerKey));
        }
        return registered;
    }

    /** SOLO 工具面保留的 db 工具（V32 定稿）：查询 + 数据变更 + 表结构辅助；结构变更（db_ddl）只属于 DB 工作台 */
    private static final Set<String> SOLO_DB_TOOLS = Set.of("db_query", "db_dml", "db_schema");

    /**
     * 工作区绑定的数据库资源 → 直注册 DbTools（V32 定稿：db 能力不走内置 MCP 类型）。
     * <p>
     * DbTools 是 @Tool 注解 bean：整 bean 注册后移除 SOLO 工具面之外的
     * （db_ddl 只属于 DB 工作台；db_schema/db_status/db_report/db_dashboard 不进 SOLO）。
     * 场景绑定已提供 db 工具时整体让位（场景边界优先，与 ops 同一语义）。
     */
    private static List<String> registerDbBinding(io.agentscope.core.tool.Toolkit toolkit,
                                                  String workspaceId, String serverKey,
                                                  com.xinl.easyclaw.tools.DbTools dbTools) {
        if (serverKey == null || serverKey.isBlank()) {
            return List.of();
        }
        if (dbTools == null) {
            log.warn("工作区 {} 绑定了数据库资源 {}，但 DbTools 未装配，能力不生效", workspaceId, serverKey);
            return List.of();
        }
        Set<String> present = toolkit.getToolSchemas().stream()
                .map(io.agentscope.core.model.ToolSchema::getName)
                .collect(java.util.stream.Collectors.toSet());
        if (present.contains("db_query") || present.contains("db_dml") || present.contains("db_schema")) {
            log.warn("工具 db_query/db_dml 已由场景绑定的 MCP 实例提供，工作区数据库绑定让位"
                            + "（workspaceId={}, 绑定的db={}）；请二选一配置，避免歧义",
                    workspaceId, serverKey);
            return List.of();
        }
        toolkit.registration().tool(dbTools).apply();
        for (String name : toolkit.getToolNames()) {
            if (name.startsWith("db_") && !SOLO_DB_TOOLS.contains(name)) {
                toolkit.removeTool(name);
            }
        }
        log.info("工作区资源绑定已直注册 db 工具 {}: workspaceId={}, dbServerKey={}",
                SOLO_DB_TOOLS, workspaceId, serverKey);
        return List.copyOf(SOLO_DB_TOOLS);
    }

    /**
     * 单个资源绑定 → 工具注册。
     *
     * @param mcpType   内置类型标识（与 {@code BuiltinMcpType.typeId()} 对齐）
     * @param kind      给人看的资源类别名（日志与告警用）
     * @param serverKey 绑定的 hub 侧资源标识；空 = 本工作区无该能力，返回空清单
     */
    private static List<String> registerBoundResource(io.agentscope.core.tool.Toolkit toolkit,
                                                      com.xinl.easyclaw.mcp.type.BuiltinMcpRegistry registry,
                                                      String workspaceId,
                                                      String mcpType, String kind, String serverKey) {
        if (serverKey == null || serverKey.isBlank()) {
            return List.of();
        }
        com.xinl.easyclaw.mcp.type.BuiltinMcpType type = registry.find(mcpType).orElse(null);
        if (type == null) {
            log.warn("工作区 {} 绑定了{}资源 {}，但内置类型 [{}] 未注册，能力不生效",
                    workspaceId, kind, serverKey, mcpType);
            return List.of();
        }
        String config;
        try {
            // 用序列化而不是拼字符串：serverKey 由 hub 侧生成或人手填，含引号时拼接会产出坏 JSON，
            // 而坏配置要到工具真正解析时才暴露，故障离因很远。
            config = BINDING_JSON.writeValueAsString(Map.of("serverKey", serverKey.trim()));
        } catch (Exception e) {
            log.warn("工作区 {} 的{}资源绑定序列化失败，跳过: {}", workspaceId, kind, e.getMessage());
            return List.of();
        }
        // 合成实例不落库：它是「工作区绑定」的运行时视图，不是用户创建的 MCP 服务。
        // id 取负数与真实服务区分，日志里也能一眼看出来源。
        com.xinl.easyclaw.mcp.entity.McpServiceEntity synthetic =
                com.xinl.easyclaw.mcp.entity.McpServiceEntity.builder()
                        .id(-1L)
                        .name("workspace-" + mcpType + "-binding")
                        .transport("BUILTIN")
                        .mcpType(mcpType)
                        .scope("WORKSPACE")
                        .workspaceId(workspaceId)
                        .implementationConfig(config)
                        .isConnected(true)
                        .build();
        Set<String> present = toolkit.getToolSchemas().stream()
                .map(io.agentscope.core.model.ToolSchema::getName)
                .collect(java.util.stream.Collectors.toSet());
        List<String> registered = new java.util.ArrayList<>();
        for (io.agentscope.core.tool.AgentTool tool : type.toolsFor(synthetic)) {
            if (present.contains(tool.getName())) {
                // 同名让位：框架 ToolRegistry 按名注册、后者覆盖前者。若场景绑定的同类实例已经
                // 提供了这个工具，静默覆盖会让「这个 AI 到底连哪台机器」由注册顺序决定，不可解释。
                // 选择让位并告警——两条配置冲突应由人消除，不由顺序裁决。
                log.warn("工具 [{}] 已由场景绑定的 MCP 实例提供，工作区资源绑定让位"
                                + "（workspaceId={}, 绑定的{}={}）；请二选一配置，避免歧义",
                        tool.getName(), workspaceId, kind, serverKey);
                continue;
            }
            toolkit.registerAgentTool(tool);
            registered.add(tool.getName());
            log.info("工作区资源绑定已派生内置能力工具 [{}]: workspaceId={}, {}={}",
                    tool.getName(), workspaceId, kind, serverKey);
        }
        return registered;
    }

    /** 资源绑定配置的序列化载体（仅用于拼 implementationConfig，无共享状态） */
    private static final com.fasterxml.jackson.databind.ObjectMapper BINDING_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 忽略大小写的包含判断：服务名在页面绑定与场景绑定里可能大小写不一致 */
    private static boolean containsIgnoreCase(Collection<String> names, String target) {
        if (names == null || target == null) {
            return false;
        }
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

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
            // 不可绕过的工具（remote_shell）即使被「永久允许」也必须保留 ASK：
            // 它现在能经内置 ops 能力实例进入 solo 场景，沿用下面的跳过逻辑，
            // 「在生产服务器上免确认执行命令」就成了一个配置层面就能触发的安全降级
            if (alwaysAllowed.contains(tool) && !ToolPermissionPolicy.nonBypassable().contains(tool)) {
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
