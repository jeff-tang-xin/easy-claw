package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.knowledge.KnowledgeEntry;
import com.xinl.easyclaw.knowledge.KnowledgeService;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主控 {@link io.agentscope.harness.agent.HarnessAgent} 系统提示词拼装器。
 * <p>
 * 从 {@code WorkspaceAgentBuilder} 抽出的单一职责组件：**只负责「system prompt 怎么拼」**，
 * 与「Agent 怎么装配」解耦。拼装顺序：默认人格 + 主控 SPI 人格 + 子 Agent 名册/派发机制
 * + Skill/模式片段 + 场景编排（环境/能力边界/方法论）+ 云端知识库索引（仅 cloud 模式）。
 * <p>
 * 顺序有意义：越靠后的片段越具体，模型对靠后的指令更敏感。主控人格排在基础提示词之后、
 * 场景之前——人格定义“你是谁”（相对稳定），场景定义“当前在做什么”（更具体，应当能覆盖
 * 人格的默认倾向）。
 * <p>
 * 抽成独立组件的第二个目的：team/schedule 编排改由 cloud 供给后，提示词拼装是主要的
 * 模式定制点，独立成类便于在此分叉，而不必改动装配器。
 * <p>
 * <b>依赖约束</b>：本类处于 WorkspaceManager → WorkspaceAgentBuilder → 本类的装配链上，
 * 禁止注入 WorkspaceManager（会循环）；projectId 经 WorkspaceRepository 直查 DB。
 */
@Component
public class SystemPromptComposer {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptComposer.class);

    private final AgentFactory agentFactory;
    private final AgentScopeProperties agentScopeProperties;
    private final ScenarioResolver scenarioResolver;
    private final com.xinl.easyclaw.agent.spi.AgentRegistry agentRegistry;
    private final CloudProperties cloudProperties;
    private final KnowledgeService knowledgeService;
    private final WorkspaceRepository workspaceRepository;

    public SystemPromptComposer(AgentFactory agentFactory,
                                AgentScopeProperties agentScopeProperties,
                                ScenarioResolver scenarioResolver,
                                com.xinl.easyclaw.agent.spi.AgentRegistry agentRegistry,
                                CloudProperties cloudProperties,
                                KnowledgeService knowledgeService,
                                WorkspaceRepository workspaceRepository) {
        this.agentFactory = agentFactory;
        this.agentScopeProperties = agentScopeProperties;
        this.scenarioResolver = scenarioResolver;
        this.agentRegistry = agentRegistry;
        this.cloudProperties = cloudProperties;
        this.knowledgeService = knowledgeService;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * 拼装系统提示词：默认人格 + 主控人格 + 子 Agent 名册/协作引导 + Skill/模式片段 + 场景编排。
     */
    public String compose(String workspaceId, List<SubagentDeclaration> subagents,
                          String sysPromptAugment, ScenarioBinding binding) {
        String sysPrompt = agentFactory.defaultSystemPrompt();

        // 激活场景只查一次：人格解析与场景注入共用（避免重复查库）
        com.xinl.easyclaw.base.profile.ScenarioProfile activeScenario =
                scenarioResolver.activeScenario(workspaceId);

        // 主控人格：角色系统下线后（方案 C）由 SPI 智能体的内置 persona 提供，
        // 不再读取 DB 角色，由 resolveMainPersona(activeScenario) 统一解析。
        String personaPrompt = resolveMainPersona(activeScenario);
        if (personaPrompt != null) {
            sysPrompt = sysPrompt + "\n\n" + personaPrompt;
            log.info("主控人格已注入 system prompt: workspace={}, {} chars",
                    workspaceId, personaPrompt.length());
        }

        if (!subagents.isEmpty()) {
            sysPrompt = sysPrompt + subagentRoster(subagents,
                    binding != null && binding.isOrchestratedMode(),
                    agentScopeProperties.getAgent().getSubagentSteps());
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
                .build(activeScenario, subagents,
                        capabilityRecommendation(binding, subagents));
        if (scenarioAugment != null) {
            sysPrompt = sysPrompt + "\n\n" + scenarioAugment;
            log.info("场景已注入 system prompt: workspace={}, augment={} chars",
                    workspaceId, scenarioAugment.length());
        }

        // 云端知识库索引（仅 cloud 模式）：框架的 WorkspaceContextMiddleware 只读本地
        // knowledge/KNOWLEDGE.md，而 cloud 模式知识全存 hub、spoke 不落盘 → 本地注入为空，
        // AI 开局对知识库毫无「目录感」。此处把 hub 条目清单（topic+summary）拼进系统提示，
        // 与 local 模式的注入语义对齐。local 模式跳过（框架已注入，重复会双份）。
        String knowledgeIndex = cloudKnowledgeIndex(workspaceId);
        if (knowledgeIndex != null) {
            sysPrompt = sysPrompt + "\n\n" + knowledgeIndex;
            log.info("云端知识库索引已注入 system prompt: workspace={}, {} chars",
                    workspaceId, knowledgeIndex.length());
        }

        return sysPrompt;
    }

    /**
     * 云端知识库索引段（仅 cloud 模式）：拉 hub 条目清单渲染为 Markdown。
     * <p>
     * 失败语义：hub 不可达 / projectId 缺失 / 清单为空清单异常时返回 {@code null} 跳过注入
     * （记 warn）——知识索引是增强信息，绝不能让 Agent 构建失败。清单是装配时快照，
     * 会话中途写入的新条目由 AI 用 {@code knowledge_list} 现查，与 local 模式
     * 「KNOWLEDGE.md 也是会话开始时快照」的行为一致。
     */
    private String cloudKnowledgeIndex(String workspaceId) {
        String appKey = cloudProperties.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            return null; // local 模式：框架已注入本地 KNOWLEDGE.md
        }
        Long projectId = workspaceRepository.findById(workspaceId)
                .map(WorkspaceEntity::getProjectId)
                .orElse(null);
        if (projectId == null) {
            log.debug("云端知识库索引跳过：工作区未绑定项目 workspace={}", workspaceId);
            return null;
        }
        List<KnowledgeEntry> entries;
        try {
            entries = knowledgeService.list(WorkspaceContext.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .build());
        } catch (Exception e) {
            log.warn("云端知识库索引拉取失败，跳过注入: workspace={}, {}", workspaceId, e.getMessage());
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 📚 云端知识库索引\n\n")
                .append("本工作区的知识库存储在 hub（绑定项目下），本地 `knowledge/` 目录为空、")
                .append("系统提示不含本地知识注入。以下是当前条目清单（Agent 装配时快照；")
                .append("会话中途新写入的条目用 `knowledge_list` 现查）：\n\n");
        if (entries.isEmpty()) {
            sb.append("（当前为空——任务收尾时值得留存的结论请用 `knowledge_write` 沉淀。）\n");
        } else {
            for (KnowledgeEntry e : entries) {
                sb.append("- **").append(e.topic()).append("**");
                if (e.summary() != null && !e.summary().isBlank()) {
                    sb.append(" — ").append(e.summary());
                }
                sb.append('\n');
            }
            sb.append("\n用 `knowledge_read(topic)` 展开正文、`knowledge_search(关键词)` 全文检索；")
                    .append("`knowledge/` 在沙箱禁读路径内，不要尝试用 read_file 直读。\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 解析主控人格，优先级：场景绑定 roleName → 模式主控声明 → main。
     * <p>
     * <b>为什么不能只看 main</b>：主控实例的装配 agentId 恒为 main（state 目录与
     * 存量会话兼容），若人格也硬编码 main，场景绑定具体智能体（roleName）就只是
     * 编排计划层的摆设——用户「给场景选智能体」永远不生效。人格是「智能体被选中」
     * 的实质语义，必须跟随绑定。
     * <p>
     * 解析链任一环失效（roleName 未注册 / mode 未注册 / SPI 缺失）都告警并回退
     * 下一环，最终兜底 main——与工具装配的「宁可少给，不可多给」取向一致：
     * 人格回退只影响说话方式，不会放大能力。
     *
     * @param scenario 激活场景，可为 null（无绑定 → 回退 main 人格）
     */
    private String resolveMainPersona(com.xinl.easyclaw.base.profile.ScenarioProfile scenario) {
        try {
            // 1. 场景绑定优先：用户给场景选了具体智能体
            if (scenario != null) {
                String roleName = scenario.getRoleName();
                if (roleName != null && !roleName.isBlank()
                        && !SubagentLoader.MAIN_AGENT_ID.equals(roleName)) {
                    var bound = agentRegistry.find(roleName);
                    if (bound.isPresent()) {
                        String persona = personaOf(bound.get());
                        if (persona != null) {
                            return persona;
                        }
                    } else {
                        log.warn("场景绑定的智能体 [{}] 未在 SPI 注册，人格回退模式主控/main"
                                        + "（检查 agent 模块是否在 classpath）",
                                roleName);
                    }
                }

                // 2. 模式主控声明（如 ops → OpsAgent）；roleName 显式绑了 main 时跳过
                var modeMain = com.xinl.easyclaw.base.orchestration.OrchestrationModes
                        .find(scenario.getMode())
                        .map(com.xinl.easyclaw.base.orchestration.AgentOrchestrator::mainAgentId)
                        .filter(id -> !SubagentLoader.MAIN_AGENT_ID.equals(id))
                        .flatMap(agentRegistry::find);
                if (modeMain.isPresent()) {
                    String persona = personaOf(modeMain.get());
                    if (persona != null) {
                        return persona;
                    }
                }
            }

            // 3. 兜底：通用主控人格
            return agentRegistry.find(SubagentLoader.MAIN_AGENT_ID)
                    .map(this::personaOf)
                    .filter(p -> p != null && !p.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("解析主控 SPI 人格失败，退回纯基础提示词: {}", e.getMessage());
            return null;
        }
    }

    /** 取智能体内置人格；空人格返回 null（调用方继续走回退链） */
    private String personaOf(com.xinl.easyclaw.base.agent.EasyClawAgent agent) {
        String persona = agent.prompt(new com.xinl.easyclaw.base.agent.AgentContext(
                null, null, false)).persona();
        return (persona != null && !persona.isBlank()) ? persona : null;
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
     * <p>
     * 设为包级静态、fallback 步数由参数传入：便于 {@code TeamModeGuideFormatTest}
     * 不依赖 Spring/Builder 直接校验文本渲染（模板用 {@code .formatted}，游离的 % 会在
     * 运行时炸 Agent 构建，编译期发现不了）。
     *
     * @param teamMode      true 时省略「你是执行者」的默认定位——该定位由编排层改写为协调者
     * @param fallbackSteps 名册内未声明 steps 时的兜底步数（来自 yml 配置）
     */
    static String subagentRoster(List<SubagentDeclaration> subagents, boolean teamMode,
                                 int fallbackSteps) {
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
                .orElse(fallbackSteps);
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
}
