package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SessionRegistry;
import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.middleware.CompactionNoticeMiddleware;
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
                                 com.xinl.easyclaw.memory.flush.ScopedMemoryFlushService scopedMemoryFlushService) {
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
                              String sysPromptAugment) {
        return build(workspaceId, SubagentLoader.MAIN_AGENT_ID, name, workspacePath, easyClawDir,
                sysPromptAugment);
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
        ScenarioBinding binding = scenarioResolver.activeBinding(workspaceId);
        // 把绑定的 MCP 服务名展开成工具名，供子 Agent 工具白名单使用。
        // 必须在 loadMerged 之前完成：白名单一刀切，只有档位工具 ∪ MCP 工具并起来才完整。
        if (binding.hasToolBinding()) {
            binding = binding.withMcpTools(mcpToolExpander.expand(binding.mcpServices()));
        }
        if (!binding.isEmpty()) {
            log.info("场景能力绑定已生效: workspace={}, {}", workspaceId, binding);
        }

        // 多 Agent 编排：子 Agent 名单由 SPI 提供（.md 扫描已下线，SPI 是唯一来源）
        List<SubagentDeclaration> subagents = subagentLoader.loadMerged(binding);

        // 角色系统下线后（方案 C），主控人格走 SPI MainAgent、模型走 yml/SPI，不再解析 DB 角色。

        // 记忆账簿用户级设置（memory_settings 表，缺省按实体默认值落库一行）。
        // 读出的配置决定本 Agent 的压缩窗、记忆提取触发器与子 Agent 记忆 hooks 开关。
        com.xinl.easyclaw.memory.settings.MemorySettingsEntity memorySettings =
                memorySettingsService.getOrCreate();

        String sysPrompt = composeSystemPrompt(workspaceId, subagents, sysPromptAugment, binding);

        Model agentModel = resolveAgentModel(agentId);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(harnessName(workspaceId, agentId))
                .description(name)
                .sysPrompt(sysPrompt)
                .model(agentModel)
                .toolkit(agentFactory.createWorkspaceToolkit(
                        binding.hasMcpBinding() ? binding.mcpServices() : null))
                // workspace 根 = .easyClaw/agent：AGENTS.md/MEMORY.md/skills/运行时数据全部集中于此
                .workspace(agentRoot)
                .filesystem(fsSpec)
                .permissionContext(buildPermissionContext(workspaceId))
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
                .middleware(new InterventionMiddleware(messageBus))
                // 压缩感知：vendored CompactionMiddleware 压缩时只写 DEBUG 日志，本类在摘要
                // 消息进入推理输入时向 UI 推送可见提示（CustomEvent → context 事件 → 前端
                // note 段 + 转录 SYSTEM 落盘）。只读推理输入，不改写，放链尾。
                .middleware(new CompactionNoticeMiddleware());

        // ===== 记忆账簿策略（用户级配置生效点）=====
        // 子 Agent 默认关闭记忆 hooks（每回合提取 + 周期合并）：子的上下文防爆由
        // compaction 负责（不受影响），值得沉淀的结论经黑板归口主 Agent 统一沉淀，
        // 避免多写手提权混写主 MEMORY.md。subagentFlushEnabled=true 时与主 Agent 同策略。
        // 主 Agent 按 flushMode 装配提取触发器——vendored 默认 ALWAYS 每回合提取一次
        // （载荷 = 会话上下文 + MEMORY.md 全文 + 当日流水全文，随记忆增长自膨胀，
        //  曾致回合收尾被提取调用绑架挂起），默认降为 throttled 30 分钟。
        if (shouldEnableMemoryHooks(agentId, memorySettings)) {
            // vendored flush 已由 toMemoryConfig 置 NEVER；每回合提取改由 web 侧
            // ScopedMemoryFlushMiddleware 接管（游标框定窗口 + 同步/异步开关 + minGap
            // 节流），杜绝 vendored「全量载荷随记忆自膨胀 + concatWith 拖住主流」的卡提取模式。
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

    // ==================== 系统提示词 ====================

    /**
     * 拼装系统提示词：默认人格 + 主控人格 + 子 Agent 名册/协作引导 + Skill/模式片段 + 场景编排。
     * <p>
     * 顺序有意义：越靠后的片段越具体，模型对靠后的指令更敏感。
     * 主控人格排在基础提示词之后、场景之前——人格定义“你是谁”（相对稳定），
     * 场景定义“当前在做什么”（更具体，应当能覆盖人格的默认倾向）。
     */
    private String composeSystemPrompt(String workspaceId, List<SubagentDeclaration> subagents,
                                       String sysPromptAugment, ScenarioBinding binding) {
        String sysPrompt = agentFactory.defaultSystemPrompt();

        // 主控人格：角色系统下线后（方案 C）由 SPI MainAgent 的内置 persona 提供，
        // 不再读取 DB 角色，由 resolveMainPersona() 统一解析。
        String personaPrompt = resolveMainPersona();
        if (personaPrompt != null) {
            sysPrompt = sysPrompt + "\n\n" + personaPrompt;
            log.info("主控人格已注入 system prompt: workspace={}, {} chars",
                    workspaceId, personaPrompt.length());
        }

        if (!subagents.isEmpty()) {
            sysPrompt = sysPrompt + subagentRoster(subagents, binding != null && binding.isOrchestratedMode());
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

    /**
     * 子 Agent 名册与派发机制约束。
     * <p>
     * <b>只讲「有哪些成员、派发工具怎么用」，不讲「该不该派、按什么计划派」</b>——
     * 后者属编排层，team 模式下由 {@code OrchestrationPromptBuilder} 独家负责。
     * 早期两处都写编排规则，主控会在「自主拆解」与「照工作流执行」之间摇摆，
     * 因为两套规则都自称最终准则；拆开后 team 模式的行为准则只有一个来源。
     *
     * @param teamMode true 时省略「你是执行者」的默认定位——该定位由编排层改写为协调者
     */
    private String subagentRoster(List<SubagentDeclaration> subagents, boolean teamMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 🤝 可调度的子 Agent\n")
                .append(teamMode
                        ? "以下子 Agent 是你可以派发任务的执行体（通过 subagent 工具）：\n"
                        : "遇到可独立交付的子任务时，你可以把它派给以下子 Agent（通过 subagent 工具）；"
                                + "简单任务自己做即可，不必为了用而用：\n");
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
                
                派发机制约束（怎么用工具，不是「该派谁」）：
                1. **同一阶段一次性全部派发**：把该阶段的所有子任务放在**同一轮**里一起发出
                   （同一轮的多个 `agent_spawn` 由框架并发执行），全部返回后再统一验收。
                   **绝不要**「派一个 → 等一个 → 再派下一个」，那会让并行退化成串行。
                   派发一律**同步等待**：工具返回时结果已经在手里。这样子 Agent 的思考与
                   工具调用会实时显示给用户，进度可见；后台任务模式（`timeout_seconds=0`）
                   已被系统统一矫正为同步，传了也不生效。
                   **`timeout_seconds` 不用传**：系统已统一注入 **1800 秒（30 分钟）** 的等待预算，
                   你传的值会被平台覆盖、不生效，传了也没有副作用。不必再为子任务估算时间预算。
                2. **`label` 必须传，且按「agentId-阶段号」命名**（如 `coder-s2`、`reviewer-s3`）：
                   框架按 `(父会话, agent_id, label)` 推导子 Agent 实例身份。
                   **不传 label 时，同一轮里派两个同名成员会被判定为同一个实例**——第二次派发不会新建，
                   而是复用第一个并把新任务追加进它的会话，表现为「并行静默退化成串行、上下文互相污染」，
                   且返回 `status: accepted (reused)` 不报错，极难发现。
                   按此命名可一举两得：① 同阶段并行的同名智能体天然拿到不同 label，互不干扰；
                   ② **返工重派时沿用同一个 label**，它能接着上次的会话干，不必从零重读代码。
                3. **不要调用屏障/轮询工具**：派发是同步的，工具全部返回时天然已「等齐」。
                   `wait_async_results` / `task_output` / `task_list` 只在后台任务模式下有意义，
                   同步派发不产生 `task_id`，调了只会拿到空结果并白烧步数。
                4. **任务最小化——一个子任务只做一件事**：子任务会撞上**两道独立的墙**，
                   两道都会让产出化为乌有，派活前必须同时估算：
                   ① **步数墙**——每个子 Agent 有迭代步数上限（当前 %d 步，派发时无法调整）。
                      注意「步数」= ReAct 轮次，**不等于工具调用次数**：一轮里并行调 5 个工具只算 1 步，
                      所以真正要估的是「要往返推理多少轮」，不是「要调多少次工具」。
                      耗尽会被强制截断、产出半成品，而半成品往往看起来像成品，最容易被误当结论。
                   ② **时间墙**——1800 秒到点直接掐断，比步数耗尽更惨：
                      步数耗尽还能强行总结吐点东西，超时掐断**连最后陈述的机会都没有**。
                      1800s 对绝大多数子任务绰绰有余，正常不会撞上；真撞上说明任务本身过大，
                      应当拆阶段，而不是指望调大超时（该值已是框架硬上限）。
                   **粒度判据**：预估轮次超过步数上限的三分之二就必须拆阶段。
                   **拆分判据**：这个任务能不能用一句话说清「做完什么就算完」？说不清就说明还能再拆。
                   ① 一次只给一个明确目标——「查 A 并改 B 再验证 C」必须拆成三个任务或三个阶段；
                   ② 宁可多派几个小任务并行，也不要派一个大任务串行——小任务失败了只需重做一小块；
                   ③ 必须带齐上下文（相关文件路径、已知结论、验收标准），子 Agent 看不到你的对话历史，
                      省略上下文会让它把步数浪费在到处找文件上。
                5. **派活必须写清交付物——什么阶段交什么结果**：模糊的任务描述是返工的头号原因。
                   每个子任务的描述里都要包含这四段，缺一段就可能拿回没法用的东西：
                   ```
                   【目标】一句话说清做完什么算完（可验证，不是「优化一下」这种）
                   【上下文】相关文件路径 + 已确认的事实 + 明确不用再查的部分
                   【交付物】要什么形态：结论清单 / 修改后的文件 / 带行号的证据 / 方案对比表
                   【中间结论】哪些结论一得出就立刻写黑板，不要等任务结束（下游成员在等）
                   ```
                   **交付物要可验证**：要「给出文件名+行号+问题描述」，而不是「看看有没有问题」；
                   要「改完并编译通过」，而不是「改一下」。验收标准写不出来，说明任务还没想清楚。
                6. **需要时为子 Agent 指定 skill**：你的上下文里有可用 skill 目录（Available Skills）。
                   若某个子任务有对应的方法论 skill（如代码评审用 `clean-code`、重构用 `code-refactor`），
                   **在任务描述开头明确写一行**：「请先用 `load_skill_through_path(skillId="<skill-id>", path="SKILL.md")`
                   加载 <skill 名> skill，并按其标准执行」。
                   **必须给出准确的 skill-id**（从你上下文的目录里原样复制），写错名字子 Agent 会加载失败并白烧步数。
                   不确定有没有合适的 skill 就不要指定——硬塞一个不相干的 skill 只会挤占它的步数。
                
                共享黑板（blackboard）——团队的公共记录本，也是抗截断的唯一持久化通道：
                并行子 Agent 之间彼此看不到对方的对话，黑板是你们唯一的共享载体。
                更关键的是：子 Agent 的对话上下文随任务结束即消失，**步数耗尽被截断时，
                它没来得及汇报的工作会全部丢失——只有已写进黑板的内容能幸存**。
                因此黑板不是「收尾时的记录动作」，而是边做边落盘的保险。
                **每个子 Agent 的系统提示里都已写入黑板协作要求**，他们知道要读、要登记，
                你不必在任务描述里重复解释黑板怎么用，但仍应做好这四件事：
                1. **开工前先读**：派活或动手前先 `blackboard_read`，看同伴已登记的结论，避免重复劳动与结论冲突。
                2. **随手就记**：得出结论、建议或风险时立即 `blackboard_append`，不要攒到收尾才写——同伴可能正等着它。
                3. **用黑板提前解锁下游**：这是并行提速的关键。若某个子任务的产出会被下一阶段用到，
                   在任务描述里明确要求它**一得出中间结论就写黑板**，而不是憋到任务结束才随最终回复带出。
                   这样下游成员读黑板就能提前开工，无需等整个上游任务收尾。
                4. **子 Agent 返回异常时先读黑板**：回复被截断、内容偏短、或明显没做完时，
                   先 `blackboard_read` 捞取它落盘的中间结论，再决定续派还是拆分重派，
                   不要直接判定「这次白干了」而原样重试。
                5. **只记结论**：写结论、建议、风险、待决问题；不要把过程日志、中间草稿、大段代码倒进去。
                6. **只增不改**：黑板是追加式的，没有删除工具，也不要试图否定或覆盖他人条目；有异议就追加一条说明理由。
                """.formatted(minSteps));
        return sb.toString();
    }

    // ==================== 模型与权限 ====================

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
     * 取主控（SPI {@code MainAgent}）内置人格片段。查不到时返回 null（退回纯基础提示词）。
     */
    private String resolveMainPersona() {
        try {
            return agentRegistry.find(SubagentLoader.MAIN_AGENT_ID)
                    .map(a -> a.prompt(new com.xinl.easyclaw.base.agent.AgentContext(
                            null, null, false)).persona())
                    .filter(p -> p != null && !p.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("解析主控 SPI 人格失败，退回纯基础提示词: {}", e.getMessage());
            return null;
        }
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
