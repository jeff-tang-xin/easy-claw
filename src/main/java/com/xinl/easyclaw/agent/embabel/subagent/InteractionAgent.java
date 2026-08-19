package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.SkillGoalFactory;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Agent(
        name = "interaction-agent",
        description = "用户交互：等待确认、收集批注、展示预览",
        scan = true
)
@Component
public class InteractionAgent {

    private static final Logger log = LoggerFactory.getLogger(InteractionAgent.class);

    private final SkillGoalFactory skillGoalFactory;

    public InteractionAgent(SkillGoalFactory skillGoalFactory) {
        this.skillGoalFactory = skillGoalFactory;
    }

    private String buildSkillContext(OperationContext ctx) {
        WorkspaceContextData ws = ctx.last(WorkspaceContextData.class);
        if (ws == null || ws.activeSkills() == null || ws.activeSkills().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 适用技能规范\n\n");
        for (String skillName : ws.activeSkills()) {
            try {
                SkillGoalFactory.SkillMeta meta = skillGoalFactory.parseSkillMeta(skillName, ws.workspaceId());
                if (!meta.description().isBlank()) {
                    sb.append("### ").append(meta.name()).append("\n\n");
                    sb.append(meta.description()).append("\n\n");
                }
            } catch (Exception e) {
                log.debug("加载 skill 失败: {}", skillName);
            }
        }
        return sb.toString();
    }

    private LlmOptions resolveLlm(OperationContext ctx) {
        WorkspaceContextData d = ctx.last(WorkspaceContextData.class);
        return d != null ? d.resolveLlmOptions() : null;
    }

    @Action(
            description = "生成审阅请求，邀请用户对内容进行批注和反馈",
            readOnly = true
    )
    @AchievesGoal(description = "等待用户批注和反馈", export = @Export(name = "sub:annotate"))
    public ChatResult waitForFeedback(UserInput input, OperationContext ctx) {
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                你是 InteractionAgent，负责请求用户反馈。
                生成分层次的审阅请求：
                1. 内容整体评价
                2. 具体段落/观点的批注
                3. 修改建议
                4. 是否可以进入下一阶段
                语气友好专业，问题要具体可回答。
                """ + buildSkillContext(ctx);

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .generateText("【请求审阅】\n\n" + input.getContent());

        return new ChatResult(
                reply != null ? reply : "审阅请求生成失败，请重试",
                List.of("annotate"), "interaction"
        );
    }

    @Action(
            description = "将方案呈现给用户，请求确认后再推进",
            readOnly = true
    )
    @AchievesGoal(description = "请求用户确认计划", export = @Export(name = "sub:confirm"))
    public ChatResult confirmPlan(UserInput input, OperationContext ctx) {
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                你是 InteractionAgent，负责请求用户确认。
                清晰呈现方案要点、风险、预期收益。
                列出需要确认的关键决策点。
                提供选项：同意 / 修改后同意 / 重新制定。
                """ + buildSkillContext(ctx);

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .generateText("【请求确认】\n\n" + input.getContent());

        return new ChatResult(
                reply != null ? reply : "确认请求生成失败，请重试",
                List.of("confirm"), "interaction"
        );
    }
}
