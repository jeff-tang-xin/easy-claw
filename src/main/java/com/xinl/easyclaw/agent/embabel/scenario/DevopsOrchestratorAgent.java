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
 * DevOps 助手场景 Agent（preset_devops）：
 * GOAP 只看到 devops-task / code-task / interaction-task / orchestrate。
 */
@Agent(
        name = "devops-scenario-agent",
        description = "DevOps 助手场景：代码分析 → 流水线创建 → 部署 → 监控 → 确认"
)
@Component
public class DevopsOrchestratorAgent extends BaseOrchestrator {

    public DevopsOrchestratorAgent(
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

    @Action(description = "【🥇 DevOps 任务】CI/CD 流水线管理、部署操作、回滚、系统监控。",
            post = {"devops-task", "meta:plan_started"})
    @AchievesGoal(description = "DevOps 任务处理", export = @Export(name = "devops-task"))
    public ChatResult devopsTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("devops", "DevOps", input, ctx);
    }

    @Action(description = "【🥇 代码任务】代码分析、配置修改、脚本编写。",
            post = {"code-task", "meta:plan_started"})
    @AchievesGoal(description = "代码任务处理", export = @Export(name = "code-task"))
    public ChatResult codeTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("coding", "代码", input, ctx);
    }

    @Action(description = "【用户确认】高风险操作前等待用户确认。必须在 devops-task 或 code-task 之后。",
            post = {"state:user_feedback", "meta:plan_started"})
    @AchievesGoal(description = "用户确认", export = @Export(name = "interaction-task"))
    public ChatResult interactionTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("interaction", "用户交互", input, ctx);
    }

    @Action(
            description = "【plan 收尾】生成 DevOps 任务总结。必须在至少 1 个实际工作 action 之后执行。",
            pre = {"meta:plan_started"},
            post = {"orchestrate", "goal_achieved"}
    )
    @AchievesGoal(description = "plan 收尾最终总结", export = @Export(name = "orchestrate"))
    public ChatResult finalizeTask(UserInput input, OperationContext ctx) {
        return doFinalizeText(input, ctx);
    }
}
