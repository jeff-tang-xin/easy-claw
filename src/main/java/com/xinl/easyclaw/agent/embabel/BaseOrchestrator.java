package com.xinl.easyclaw.agent.embabel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.HistoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.MemoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import com.xinl.easyclaw.mcp.service.McpToolFactory;
import com.xinl.easyclaw.scenario.ActionRegistry;
import com.xinl.easyclaw.scenario.ScenarioService;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.skill.service.SkillResolver;

/**
 * 场景编排 Agent 抽象基类：提供公共方法体（委派子 Agent / 收尾文本生成 / 规划约束构建 / 上下文解析）。
 * <p>
 * 子类各自声明 {@code @Agent} + 自己场景需要的 {@code @Action} 方法，
 * 方法体调用本类的 protected 方法（{@link #delegateToSubagent} / {@link #doFinalizeText}）。
 * 这样 GOAP planner 从一开始就只看到子类声明的 @Action，编译期确定，无需运行时过滤。
 */
public abstract class BaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BaseOrchestrator.class);

    protected final boolean enableWeb;
    protected final SkillGoalFactory skillGoalFactory;
    protected final SkillToolFactory skillToolFactory;
    protected final SkillResolver skillResolver;
    protected final SystemPromptBuilder systemPromptBuilder;
    protected final ScenarioService scenarioService;
    protected final ActionRegistry actionRegistry;
    protected final McpServiceRepository mcpRepo;
    protected final McpToolRepository mcpToolRepo;
    protected final McpToolFactory mcpToolFactory;
    protected final AgentPlatform agentPlatform;
    protected final RoleAgentFactory roleAgentFactory;
    protected final AgentProcessEventBridge eventBridge;

    public BaseOrchestrator(
            @Value("${easy-claw.tools.web.enabled:true}") boolean enableWeb,
            SkillGoalFactory skillGoalFactory,
            SkillToolFactory skillToolFactory,
            SkillResolver skillResolver,
            SystemPromptBuilder systemPromptBuilder,
            ScenarioService scenarioService,
            ActionRegistry actionRegistry,
            McpServiceRepository mcpRepo,
            McpToolRepository mcpToolRepo,
            McpToolFactory mcpToolFactory,
            AgentPlatform agentPlatform,
            @Lazy RoleAgentFactory roleAgentFactory,
            AgentProcessEventBridge eventBridge) {
        this.enableWeb = enableWeb;
        this.skillGoalFactory = skillGoalFactory;
        this.skillToolFactory = skillToolFactory;
        this.skillResolver = skillResolver;
        this.systemPromptBuilder = systemPromptBuilder;
        this.scenarioService = scenarioService;
        this.actionRegistry = actionRegistry;
        this.mcpRepo = mcpRepo;
        this.mcpToolRepo = mcpToolRepo;
        this.mcpToolFactory = mcpToolFactory;
        this.agentPlatform = agentPlatform;
        this.roleAgentFactory = roleAgentFactory;
        this.eventBridge = eventBridge;
    }

    protected WorkspaceContextData resolveWsCtx(OperationContext ctx) {
        return ctx.last(WorkspaceContextData.class);
    }

    protected Path resolveWsPath(OperationContext ctx) {
        WorkspaceContextData d = resolveWsCtx(ctx);
        return d != null && d.workspacePath() != null ? d.workspacePath()
                : Path.of(System.getProperty("user.dir"));
    }

    protected LlmOptions resolveLlm(OperationContext ctx) {
        WorkspaceContextData d = resolveWsCtx(ctx);
        return d != null ? d.resolveLlmOptions() : null;
    }

    protected ScenarioEntity resolveScenario(WorkspaceContextData ws) {
        if (ws == null || ws.scenarioId() == null) {
            return scenarioService.findById("preset_general").orElse(null);
        }
        return scenarioService.findById(ws.scenarioId()).orElse(null);
    }

    /**
     * ★ 此方法已不再挂 @Action / @AchievesGoal（2026-08-19 根治『AI 消息显示两遍』）
     *
     * 根因：之前 chat() 同时承担两个角色：
     *   ① RoleAgentFactory 把 OrchestratorAgent 注册为 default/orchestrator 根 Agent → Embabel 启动根 Agent 后，
     *     看到第一个 post=goal_achieved 的 @Action 就会把它当作『单步兜底 action』直接跑（入口调用）；
     *   ② GOAP planner 又把 chat() 选进 plan 当『收尾 goal_achieved step』再跑一次；
     *   两次调用都 saveMessage 相同的 clamp 短文本 → UI 上一模一样显示两遍。
     *
     * 根治：chat() 彻底『去 @Action 化』，Embabel 启动根 Agent 后没单步兜底可用，只能先生成 GOAP plan；
     *       plan 的唯一收尾 step 由新的 finalizeTask() 承担（只挂收尾注解，不挂入口语义）。
     */
    protected ChatResult doFinalizeText(UserInput input, OperationContext ctx) {
        WorkspaceContextData ws = resolveWsCtx(ctx);
        LlmOptions llm = resolveLlm(ctx);
        ScenarioEntity scenario = resolveScenario(ws);

        var runner = ctx.ai().withDefaultLlm();
        if (llm != null) runner = runner.withLlm(llm);

        Map<String, Object> constraints = scenarioService.resolvePlanConstraints(scenario);
        runner = runner.withSystemPrompt(systemPromptBuilder.build(ws, resolveWsPath(ctx), scenario,
                ctx.last(HistoryContextData.class), ctx.last(MemoryContextData.class))
                + buildPlanningPromptBlock(constraints, scenario)
                + """

                ------- 【ORCHESTRATOR OUTPUT CONSTRAINTS - 输出严格约束】-------
                你现在输出的内容将作为**本次对话的最终助手消息**，会保存到消息列表并直接显示给用户。注意：
                1. ⛔ 绝对禁止复述 / 重述 / 总结 / 展开之前任何 Action、Tool、Subagent 已执行的过程。
                   用户在右侧「⚡执行流面板」和过程消息中已经看到了全部细节，不需要你重复。
                   → 禁止使用"下面是 / 让我为你整理 / 本次执行过程 / 具体操作包括 / 总结如下："这类开头。
                2. 📏 除非是对"开放式写作 / 长文生成 / 报告输出"类用户需求的直接作答，否则：
                   回复长度上限 **120 个中文字符**（或 240 个英文字符）。超长的过程性内容将被系统自动截断。
                3. ✅ 收尾类任务（计划已完成）时，推荐的回执格式（简短即可，不是必须）：
                     ✅ 已完成 N 项任务（xx·yy·zz），详情请见上方 ⚡执行流。
                   有失败项：
                     ✅ 已完成 N 项，⚠️ M 项存在提示（或失败），请查看执行流中 ⚠️ 条目。
                4. 💬 如果用户的需求是开放式（如写报告、翻译、生成文档、回答问题）本身就需要长文本，
                   你可以正常输出长文，但仍然**不要复述执行过程本身**。
                5. ⛔ 不要为了"确认结果"去调用任何文件读取类工具再次查看已经处理过的文件 —— 那会触发额外的 ToolLoop 迭代，被视为违规。
                ------- END CONSTRAINTS -------
                """);

        String reply = runner.generateText(input.getContent());
        reply = clampProcessSummaryIfRedundant(reply);
        if (reply == null || reply.isBlank()) {
            reply = "抱歉，我无法处理您的请求。请尝试重新描述您的需求。";
        }

        String modelUsed = ws != null && ws.roleModel() != null ? ws.roleModel() : "default";
        log.info("BaseOrchestrator.finalize(orchestrate) 完成: inputLen={}, replyLen={}", input.getContent().length(), reply.length());
        return new ChatResult(reply, List.of("orchestrate"), modelUsed);
    }

    /** 把 Scenario.planConstraints 四件套转成自然语言注入 System Prompt（PLAN 阶段就看到，不是事后 REPLAN） */
    @SuppressWarnings("unchecked")
    protected String buildPlanningPromptBlock(Map<String, Object> constraints, ScenarioEntity scenario) {
        int maxSteps = constraints.get("maxSteps") instanceof Integer i ? i : 5;
        List<String> firstStepCannotBe = constraints.get("firstStepCannotBe") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        List<String> onlyOnce = constraints.get("onlyOnce") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        Map<String, List<String>> orderRules = new LinkedHashMap<>();
        if (constraints.get("orderRules") instanceof Map<?, ?> om) {
            for (var kv : om.entrySet()) {
                if (kv.getValue() instanceof List<?> vl) {
                    orderRules.put(String.valueOf(kv.getKey()),
                            vl.stream().map(String::valueOf).toList());
                }
            }
        }
        String scenarioName = scenario != null && scenario.getName() != null ? scenario.getName() : "通用场景";
        Set<String> normEnabledExports = scenario != null
                ? scenarioService.resolveEnabledExportNames(scenario)
                : new java.util.LinkedHashSet<>(ScenarioService.AGENT_TYPE_TO_EXPORT.values());
        if (!normEnabledExports.contains(ScenarioService.EXPORT_FINALIZE)) {
            normEnabledExports.add(ScenarioService.EXPORT_FINALIZE);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n------- 【GOAP PLANNING CONSTRAINTS - 规划阶段硬约束（必须遵守，否则 plan 会被自动拒绝并触发重新规划）】-------\n");
        sb.append("当前场景: ").append(scenarioName).append("\n");
        sb.append("【Goal】找到最简单、步数最少的 plan（自动寻路，优先最少步数，不要绕路）。\n");
        sb.append("【Constraint 0 - 场景白名单 enabledActions（必须严格命中！选未启用能力会直接触发重新规划）】当前场景只允许你从以下 action 中选: ")
                .append(String.join(" / ", normEnabledExports)).append("。其他 action（如 mail-task/data-task/devops-task/research 等）本场景根本没启用，绝对不要选！\n");
        sb.append("【Constraint 1 - 最大步数 maxSteps】你的 plan 总步数最多 ").append(maxSteps).append(" 步，超过立即触发重新规划。\n");
        if (!firstStepCannotBe.isEmpty()) {
            sb.append("【Constraint 2 - 首步黑名单 firstStepCannotBe】plan 第 1 步绝对不能是: ").append(String.join(" / ", firstStepCannotBe))
                    .append("。（必须在开头就选实际工作 action，不要用 orchestrate 兜底）\n");
        }
        if (!onlyOnce.isEmpty()) {
            sb.append("【Constraint 3 - 只执行一次 onlyOnce】以下 action 在你的 plan 中最多只能出现 1 次（禁止重复）：")
                    .append(String.join(" / ", onlyOnce)).append("\n");
        }
        if (!orderRules.isEmpty()) {
            sb.append("【Constraint 4 - 先后顺序 orderRules（必须严格遵守，有向边 B→A 代表 B 必须在 A 之后执行）】\n");
            for (var kv : orderRules.entrySet()) {
                sb.append("   • action [").append(kv.getKey()).append("] 必须出现在这几个 action 之后: ")
                        .append(String.join(" / ", kv.getValue())).append("（如果 plan 里它们同时出现的话）\n");
            }
        }
        sb.append("【Constraint 5 - 通用寻路原则】\n");
        sb.append("   • orchestrate（本 chat action）必须是 plan 中最后一步收尾用，绝对不能出现在中间或首步。\n");
        sb.append("   • verify-task 必须在 code-task 或 devops-task 之后执行，绝对不能作为首步。\n");
        sb.append("   • 优先选择最少步数直达 goal，不要为了『显得详细』而加多余 step（多余 step 会被 onlyOnce/orderRules 检测为违规）。\n");
        sb.append("------- END PLANNING CONSTRAINTS -------\n");
        return sb.toString();
    }

    /**
     * 委派给指定类型的子 Agent 执行（直接启动子进程同步拿结果，消除 ToolLoop 嵌套）。
     * <p>
     * 【为什么不用 Subagent.ofClass() + runner.generateText 了】：
     * 之前的写法会把 file-agent/code-agent 作为一个"工具"注册给 Orchestrator 的 PromptRunner，
     * 然后 Orchestrator 自己的 ToolLoop（maxIter=20） 里 LLM 每次选择 tool=file-agent，
     * 进入 FileAgent 后 FileAgent 又自己的 ToolLoop（maxIter=20）自己调 readFile/editFile 等，
     * 造成嵌套 ToolLoop：20 × 20 = 400 次工具调用上限（用户截图 ×20 就是这一层）。
     * <p>
     * 现在改为：委派 @Action 被 GOAP 选中后，【agentPlatform.createAgentProcessFrom】
     * 直接创建对应子 Agent 的子进程同步运行 → resultOfType 阻塞拿 ChatResult 结果。
     * 只有子 Agent 自己 1 层 ToolLoop（×20），没有 Orchestrator 那层 ToolLoop 嵌套。
     */
    protected ChatResult delegateToSubagent(String agentType, String taskLabel,
                                            UserInput input, OperationContext ctx) {
        WorkspaceContextData ws = resolveWsCtx(ctx);
        ScenarioEntity scenario = resolveScenario(ws);

        Set<String> enabledAgentTypes = scenarioService.resolveEnabledAgentTypes(scenario);
        if (ws != null && ws.allowedAgentTypes() != null && !ws.allowedAgentTypes().isEmpty()) {
            enabledAgentTypes.retainAll(ws.allowedAgentTypes());
        }
        if (!enabledAgentTypes.contains(agentType)) {
            String msg = "⚠️ 当前场景未启用 " + taskLabel + " 能力。请切换场景或在场景编辑器中启用 "
                    + taskLabel + " Agent。";
            log.info("委派跳过 - 场景未启用: agentType={}, enabled={}", agentType, enabledAgentTypes);
            return new ChatResult(msg, List.of(agentType + "-task"), "default");
        }

        try {
            var agent = roleAgentFactory.resolveAgent(agentType);
            if (agent == null) {
                return new ChatResult("⚠️ 未注册 " + taskLabel + " Agent（resolveAgent 返回 null）。",
                        List.of(agentType + "-task"), "default");
            }

            var childOpts = ProcessOptionsFactory.forChild(eventBridge);
            Map<String, Object> childBlackboard = new LinkedHashMap<>();
            childBlackboard.put("input", new UserInput(input.getContent()));
            childBlackboard.put("workspaceCtx", resolveWsCtx(ctx));
            childBlackboard.put("historyCtx", ctx.last(HistoryContextData.class));
            childBlackboard.put("memoryCtx", ctx.last(MemoryContextData.class));

            // 同步调用：必须在 Embabel 进程上下文中执行，保证 parentId 正确设置，事件桥能追踪子进程
            // ProcessOptions 已内置 TimeBudgetPolicy(60s) + Budget(3步) 超时保护
            AgentProcess process = agentPlatform.runAgentFrom(agent, childOpts, childBlackboard);

            if (!process.getFinished()) {
                log.warn("委派未完成 - 进程状态非 FINISHED, 尝试 kill: agentType={}, status={}",
                        agentType, process.statusReport());
                process.kill();
                return new ChatResult("⚠️ " + taskLabel + " 执行未完成（已超时终止）。",
                        List.of(agentType + "-task"), "default");
            }

            ChatResult cr = process.resultOfType(ChatResult.class);
            String reply = cr != null && cr.reply() != null ? cr.reply() : null;
            log.info("委派完成: agentType={}, processId={}, replyLen={}",
                    agentType, process.getId(), reply != null ? reply.length() : 0);
            return new ChatResult(
                    reply != null && !reply.isBlank() ? reply : taskLabel + "任务处理失败，请重试",
                    cr != null && cr.steps() != null ? cr.steps() : List.of(agentType + "-task"),
                    cr != null && cr.modelUsed() != null ? cr.modelUsed() :
                            (ws != null && ws.roleModel() != null ? ws.roleModel() : "default")
            );
        } catch (Exception e) {
            log.warn("委派失败 - 子进程执行异常: agentType={}, err={}", agentType, e.getMessage());
            return new ChatResult("⚠️ " + taskLabel + " 任务执行失败: " + e.getMessage(),
                    List.of(agentType + "-task"), "default");
        }
    }

    /**
     * Prompt 约束没守住时的 Java 层兜底：
     * 若 orchestrate.chat() 生成的回复明显是"复述执行过程"型长文，截断到短回执 + 引导看执行流。
     * 避免 LLM 输出几千字的过程重述，把用户真正关心的子 Agent 结果挤到 UI 上方看不见。
     */
    public static String clampProcessSummaryIfRedundant(String reply) {
        if (reply == null || reply.isBlank()) return reply;
        String trimmed = reply.strip();
        String tLower = trimmed.toLowerCase();
        boolean hasRedundantHint = false;
        String[] triggers = {
                "下面是", "以下是", "总结如下", "本次执行", "执行过程", "具体操作",
                "操作步骤", "让我为你", "让我来为你", "整理如下", "完成了以下",
                "first,", "1.", "步骤一", "步骤 1", "• 第一步", "首先,"
        };
        for (String trig : triggers) {
            if (tLower.contains(trig.toLowerCase())) { hasRedundantHint = true; break; }
        }
        long lineCnt = trimmed.chars().filter(c -> c == '\n').count();
        boolean longMultiParagraph = trimmed.length() > 400 || (lineCnt >= 4 && trimmed.length() > 240);
        if (!hasRedundantHint && !longMultiParagraph) {
            return reply;
        }
        String head;
        int firstNL = trimmed.indexOf('\n');
        if (firstNL > 0 && firstNL <= 220) {
            head = trimmed.substring(0, firstNL).strip();
        } else if (trimmed.length() <= 220) {
            head = trimmed;
        } else {
            String piece = trimmed.substring(0, Math.min(240, trimmed.length()));
            int lastPunc = Math.max(
                    Math.max(piece.lastIndexOf('。'), piece.lastIndexOf('！')),
                    Math.max(piece.lastIndexOf('?'), piece.lastIndexOf('.'))
            );
            head = (lastPunc > 80 ? piece.substring(0, lastPunc + 1) : piece.substring(0, 180)).strip() + "…";
        }
        head = head.replaceAll("^[\\s#>\\-*·•]+", "")
                .replaceFirst("(?i)^(下面是|以下是|总结如下|本次任务的|本次执行的|执行(过程|结果)|让我为你(整理|总结)?|让我来为你(整理|总结)?|具体操作(如下)?|完成了以下(任务|工作)?).{0,14}[：:]\\s*", "");
        if (head.isBlank()) head = "任务执行完成";
        return "✅ " + head + "\n\n📌 详细过程与每项产出请查看右侧 ⚡执行流 面板。";
    }
}
