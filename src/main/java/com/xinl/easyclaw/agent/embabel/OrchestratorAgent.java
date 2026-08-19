package com.xinl.easyclaw.agent.embabel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import com.xinl.easyclaw.mcp.service.McpToolFactory;
import com.xinl.easyclaw.scenario.ActionRegistry;
import com.xinl.easyclaw.scenario.ScenarioService;
import com.xinl.easyclaw.skill.service.SkillResolver;

/**
 * 通用场景 Agent（preset_general）：只做开始和结束（单步 LLM 对话兜底）。
 * <p>
 * 不含任何委派 action（code-task/file-task/mail-task 等）。
 * 需要代码/文件/邮件等专项服务时，切换到对应场景 Agent。
 */
@Agent(
        name = "orchestrator-agent",
        description = "Easy-Claw 通用助手：单步 LLM 对话兜底，不委派子 Agent"
)
@Component
public class OrchestratorAgent extends BaseOrchestrator {

    public OrchestratorAgent(
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

    /**
     * 通用场景唯一 action：既是开始（入口）又是结束（收尾）。
     * 无 pre → Embabel 单步直接执行，不走 GOAP plan。
     */
    @Action(
            description = "【通用对话】直接用 LLM 回复用户，不走 GOAP 委派。适用于简单问答、闲聊、无需专项工具的场景。",
            post = {"orchestrate", "goal_achieved"}
    )
    @AchievesGoal(description = "通用对话回复", export = @Export(name = "orchestrate"))
    public ChatResult finalizeTask(UserInput input, OperationContext ctx) {
        return doFinalizeText(input, ctx);
    }
}
