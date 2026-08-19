package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.SkillGoalFactory;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Agent(
        name = "research-agent",
        description = "网络信息检索专家：搜索调研、API 发现、技术方案收集",
        scan = true
)
@Component
public class WebAgent {

    private static final Logger log = LoggerFactory.getLogger(WebAgent.class);

    private final boolean enableWeb;
    private final SkillGoalFactory skillGoalFactory;

    public WebAgent(@Value("${easy-claw.tools.web.enabled:true}") boolean enableWeb,
                    SkillGoalFactory skillGoalFactory) {
        this.enableWeb = enableWeb;
        this.skillGoalFactory = skillGoalFactory;
    }

    private String buildSkillContext(OperationContext ctx) {
        WorkspaceContextData ws = ctx.last(WorkspaceContextData.class);
        if (ws == null || ws.activeSkills() == null) return "";
        StringBuilder sb = new StringBuilder("\n\n## 适用技能规范\n\n");
        for (String name : ws.activeSkills()) {
            try {
                var meta = skillGoalFactory.parseSkillMeta(name, ws.workspaceId());
                if (!meta.description().isBlank()) {
                    sb.append("### ").append(meta.name()).append("\n\n")
                      .append(meta.description()).append("\n\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private LlmOptions resolveLlm(OperationContext ctx) {
        WorkspaceContextData d = ctx.last(WorkspaceContextData.class);
        return d != null ? d.resolveLlmOptions() : null;
    }

    @Action(
            description = "搜索网络信息：调研主题、收集资料、对比分析、API 发现",
            readOnly = true
    )
    @AchievesGoal(description = "网络调研与信息收集", export = @Export(name = "sub:research"))
    public ChatResult research(UserInput input, OperationContext ctx) {
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = "你是 ResearchAgent，负责网络调研和 API 发现。\n" +
                "先用 webSearch 搜索相关主题，再用 fetchUrl 深入阅读。\n" +
                "交叉验证信息来源，标注出处。\n" +
                "如果是 API 调研，找官方文档和示例，输出端点列表、认证方式、请求/响应格式。\n" +
                "输出格式：\n## 关键发现\n## 详细信息（按来源分组）\n## 参考链接"
                + buildSkillContext(ctx);

        runner = runner.withSystemPrompt(systemPrompt);
        if (enableWeb) runner = runner.withToolGroup(CoreToolGroups.WEB);

        String reply = runner.generateText("【调研任务】\n\n" + input.getContent());

        return new ChatResult(
                reply != null ? reply : "调研失败，请重试",
                List.of("research"), "research"
        );
    }
}
