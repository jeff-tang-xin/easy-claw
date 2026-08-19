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
 * 内容创作场景 Agent（preset_content-create）：
 * GOAP 只看到 research / content-task / interaction-task / orchestrate。
 */
@Agent(
        name = "content-create-scenario-agent",
        description = "内容创作场景：调研 → 大纲 → 初稿 → 修改 → 确认"
)
@Component
public class ContentCreateOrchestratorAgent extends BaseOrchestrator {

    public ContentCreateOrchestratorAgent(
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

    @Action(description = "【🥇 网络调研】搜索资料、技术方案、竞品信息。",
            post = {"research", "meta:plan_started"})
    @AchievesGoal(description = "网络调研", export = @Export(name = "research"))
    public ChatResult researchTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("research", "网络调研", input, ctx);
    }

    @Action(description = "【内容写作】基于调研生成内容。必须在 research 之后。",
            post = {"content-task", "meta:plan_started"})
    @AchievesGoal(description = "内容创作", export = @Export(name = "content-task"))
    public ChatResult contentTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("content", "内容", input, ctx);
    }

    @Action(description = "【用户确认】等待用户反馈确认内容。",
            post = {"interaction-task", "meta:plan_started"})
    @AchievesGoal(description = "用户确认", export = @Export(name = "interaction-task"))
    public ChatResult interactionTask(UserInput input, OperationContext ctx) {
        return delegateToSubagent("interaction", "用户交互", input, ctx);
    }

    @Action(
            description = "【plan 收尾】生成内容创作任务总结。必须在至少 1 个实际工作 action 之后执行。",
            pre = {"meta:plan_started"},
            post = {"orchestrate", "goal_achieved"}
    )
    @AchievesGoal(description = "plan 收尾最终总结", export = @Export(name = "orchestrate"))
    public ChatResult finalizeTask(UserInput input, OperationContext ctx) {
        return doFinalizeText(input, ctx);
    }
}
