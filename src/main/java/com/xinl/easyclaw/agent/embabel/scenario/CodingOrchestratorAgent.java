package com.xinl.easyclaw.agent.embabel.scenario;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.xinl.easyclaw.agent.embabel.AgentProcessEventBridge;
import com.xinl.easyclaw.agent.embabel.BaseOrchestrator;
import com.xinl.easyclaw.agent.embabel.RoleAgentFactory;
import com.xinl.easyclaw.agent.embabel.SkillGoalFactory;
import com.xinl.easyclaw.agent.embabel.SkillToolFactory;
import com.xinl.easyclaw.agent.embabel.SystemPromptBuilder;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import com.xinl.easyclaw.mcp.service.McpToolFactory;
import com.xinl.easyclaw.scenario.ActionRegistry;
import com.xinl.easyclaw.scenario.ScenarioService;
import com.xinl.easyclaw.skill.service.SkillResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 编码专家场景 Agent（preset_coding）：
 * GOAP planner 只看到 code-task / file-task / verify-task / interaction-task / orchestrate 5 个 action。
 * <p>
 * 编码场景默认不启用 mail/data/content/research/devops，避免 planner 选错。
 */
@Agent(
        name = "coding-scenario-agent",
        description = "编码专家场景智能体：专注代码分析、重构、测试、评审、文件操作、验证"
)
@Component
public class CodingOrchestratorAgent extends BaseOrchestrator {

    public CodingOrchestratorAgent(
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
        super(enableWeb, skillGoalFactory, skillToolFactory, skillResolver, systemPromptBuilder,
                scenarioService, actionRegistry, mcpRepo, mcpToolRepo, mcpToolFactory,
                agentPlatform, roleAgentFactory, eventBridge);
    }

    @Action(description = "【🥇 代码任务必选优先 action：绝对不要首选 orchestrate】用户输入涉及任何代码/编程相关（关键词：代码、函数、类、方法、Bug、重构、评审、review、test、测试、实现、开发、Java/JS/Python/TS/C++/Go/Rust 等语言名、编译器报错、编译、debug、调试、算法、逻辑、功能、模块）时，GOAP plan **必须首先选择本 action**。包含：代码理解与分析、Bug 修复、重构建议、代码评审、代码修改写入、单元测试、编译构建。",
            post = {"code-task", "meta:plan_started"})
    @AchievesGoal(description = "代码任务处理", export = @Export(name = "code-task"))
    public ChatResult codeTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("coding", "代码", input, ctx);
    }

    @Action(description = "【🥇 文件/目录任务必选优先 action：绝对不要首选 orchestrate】用户输入涉及任何文件/目录相关（关键词：文件、目录、文件夹、搜索、listFiles、find、readFile、grep、批量、重命名、移动、复制、删除、整理、归档、批量编辑、批量替换、批量修改、遍历、目录结构、路径、下载、上传）时，GOAP plan **必须首先选择本 action**。包含：文件搜索、目录整理、文件批量编辑、批量重命名、批量复制/移动、文件归档。",
            post = {"file-task", "meta:plan_started"})
    @AchievesGoal(description = "文件任务处理", export = @Export(name = "file-task"))
    public ChatResult fileTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("file", "文件", input, ctx);
    }

    @Action(description = "【🥇 验证测试任务必选优先 action：**本 action 必须出现在 code-task 之后，绝对不要作为 plan 首步**】用户输入涉及任何验证/测试/质量相关（关键词：验证、verify、编译、构建、test、测试、跑、运行测试、质量、检查、lint、报告、quality、validate、正确性）时，可以选择本 action，但必须确保在 code-task 之后执行。包含：编译检查、单元测试运行、集成测试执行、质量报告生成、代码规范检查。",
            pre = {"code-task"},
            post = {"verify-task", "meta:plan_started"})
    @AchievesGoal(description = "验证任务处理", export = @Export(name = "verify-task"))
    public ChatResult verifyTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("verify", "验证", input, ctx);
    }

    @Action(description = "【🥇 用户交互任务必选优先 action：**本 action 通常不作为首步（除非用户一开始就说需要确认），在高风险操作前必须插入**】用户输入涉及任何确认/交互/反馈/批注相关（关键词：确认、confirm、反馈、批注、annotate、询问、提示用户、等待、输入、交互、征求意见、是否、要不要、批准、同意）时，可以选择本 action。包含：等待用户反馈、确认计划、收集用户批注、与用户交互澄清需求。",
            post = {"interaction-task", "meta:plan_started"})
    @AchievesGoal(description = "用户交互处理", export = @Export(name = "interaction-task"))
    public ChatResult interactionTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("interaction", "用户交互", input, ctx);
    }

    @Action(
            description = "【plan 收尾】生成最终用户可见的总结消息（极短）保存入库。必须在至少 1 个实际工作 action（code-task/file-task/verify-task 等）之后执行，不能作为首步或单步独立运行。",
            pre = {"meta:plan_started"},
            post = {"orchestrate", "goal_achieved"}
    )
    @AchievesGoal(description = "plan 收尾最终总结（仅 plan 最后一步）", export = @Export(name = "orchestrate"))
    public ChatResult finalizeTask(UserInput input, OperationContext ctx) {
        return doFinalizeText(input, ctx);
    }
}
