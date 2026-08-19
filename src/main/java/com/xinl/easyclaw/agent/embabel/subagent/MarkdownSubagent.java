package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.domain.io.UserInput;
/**
 * 通用 Markdown 子 Agent。
 * <p>
 * 每个实例通过构造函数持有不同的 systemPrompt，再通过 {@code AgentScopeBuilder.fromInstance(...)}
 * 动态创建独立的 Embabel Agent，并用 {@code Subagent.ofInstance(...)} 注入为主 Agent 的 Subagent 工具。
 */
@Agent(
        name = "markdown-subagent-template",
        description = "动态 Markdown 子智能体：根据用户自定义 systemPrompt 执行子任务",
        scan = false
)
public class MarkdownSubagent {

    private String systemPrompt = "你是一个专业子智能体。";

    public MarkdownSubagent() {
    }

    public MarkdownSubagent(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    @Action(
            description = "执行 Markdown 子智能体的专属任务"
    )
    @AchievesGoal(description = "执行 Markdown 子智能体任务", export = @Export(name = "sub:markdown-subagent-execute"))
    public String execute(UserInput input, OperationContext ctx) {
        return ctx.ai()
                .withDefaultLlm()
                .withSystemPrompt(systemPrompt == null ? "你是一个专业子智能体。" : systemPrompt)
                .generateText(input.getContent());
    }
}
