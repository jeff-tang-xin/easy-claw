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
 * 邮件分拣场景 Agent（preset_mail-triage）：
 * GOAP 只看到 mail-task / content-task / interaction-task / orchestrate。
 */
@Agent(
        name = "mail-triage-scenario-agent",
        description = "邮件分拣场景：收集邮件 → 分类 → 摘要 → 回复/确认"
)
@Component
public class MailTriageOrchestratorAgent extends BaseOrchestrator {

    public MailTriageOrchestratorAgent(
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

    @Action(description = "【🥇 邮件任务】收集邮件、提取信息、分类整理。",
            post = {"mail-task", "meta:plan_started"})
    @AchievesGoal(description = "邮件收集分类", export = @Export(name = "mail-task"))
    public ChatResult mailTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("mail", "邮件", input, ctx);
    }

    @Action(description = "【内容写作】生成邮件摘要、回复草稿。必须在 mail-task 之后。",
            post = {"content-task", "meta:plan_started"})
    @AchievesGoal(description = "邮件摘要回复", export = @Export(name = "content-task"))
    public ChatResult contentTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("content", "内容", input, ctx);
    }

    @Action(description = "【用户确认】等待用户确认回复内容。",
            post = {"interaction-task", "meta:plan_started"})
    @AchievesGoal(description = "用户确认", export = @Export(name = "interaction-task"))
    public ChatResult interactionTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("interaction", "用户交互", input, ctx);
    }

    @Action(
            description = "【plan 收尾】生成邮件分拣任务总结。必须在至少 1 个实际工作 action 之后执行。",
            pre = {"meta:plan_started"},
            post = {"orchestrate", "goal_achieved"}
    )
    @AchievesGoal(description = "plan 收尾最终总结", export = @Export(name = "orchestrate"))
    public ChatResult finalizeTask(UserInput input, OperationContext ctx) {
        return doFinalizeText(input, ctx);
    }
}
