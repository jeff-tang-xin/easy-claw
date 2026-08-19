package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.SkillGoalFactory;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Agent(
        name = "mail-agent",
        description = "邮件专家：收集、提取、分类、发送",
        scan = true
)
@Component
public class MailAgent {

    private static final Logger log = LoggerFactory.getLogger(MailAgent.class);

    private final SkillGoalFactory skillGoalFactory;

    public MailAgent(SkillGoalFactory skillGoalFactory) {
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

    private Path resolveWsPath(OperationContext ctx) {
        WorkspaceContextData d = ctx.last(WorkspaceContextData.class);
        return d != null && d.workspacePath() != null ? d.workspacePath()
                : Path.of(System.getProperty("user.dir"));
    }

    private LlmOptions resolveLlm(OperationContext ctx) {
        WorkspaceContextData d = ctx.last(WorkspaceContextData.class);
        return d != null ? d.resolveLlmOptions() : null;
    }

    @Action(
            description = "处理邮件相关任务：收集、提取、分类、发送"
    )
    @AchievesGoal(description = "邮件任务处理", export = @Export(name = "sub:mail-task"))
    public ChatResult handleMailTask(UserInput input, OperationContext ctx) {
        Path wsPath = resolveWsPath(ctx);
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                  你是 MailAgent，一个端到端的邮件专家，负责在同一会话内依次完成邮件收集、信息提取、智能分类与邮件撰写发送。

                工作区根目录：%s

                请按以下流程依次推进（每一步都要落地到工作区文件，便于后续步骤读取）：

                【1. 邮件收集】
                - 使用 webSearch 和 fetchUrl 搜索邮件归档、邮件列表、公开联系方式
                - 交叉验证来源，标注每个邮件地址的出处
                - 输出格式：
                  ## 收集清单（来源 + 邮件地址 + 说明）
                  ## 统计汇总
                - 将收集清单保存到工作区

                【2. 信息提取】
                - 先 readFile 加载邮件列表或邮件内容
                - 从每封邮件中提取：发件人、收件人、主题、时间、正文摘要、关键词
                - 将提取结果整理为结构化数据并保存

                【3. 智能分类】
                - 先 readFile 加载已提取的邮件信息
                - 分类维度：紧急度（高/中/低）、主题（工作/个人/订阅/垃圾）、发件人关系
                - 为每封邮件给出处理建议：立即回复 / 稍后处理 / 归档 / 忽略
                - 按分类结果整理输出并保存

                【4. 邮件撰写与发送】
                - 先 readFile 加载参考内容或上下文
                - 生成专业的邮件：明确主题、得体称呼、清晰正文、礼貌结尾
                - 输出完整的邮件草稿供确认

                输出规范：
                - 用 Markdown 格式化
                - 每个阶段输出都要有明确的阶段标题
                - 结论需有来源支撑，不要只给空泛描述
                - 区分"必须处理"和"建议处理"的事项
                """.formatted(wsPath.toString()) + buildSkillContext(ctx);

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .withToolGroup(CoreToolGroups.WEB)
                .generateText("【邮件任务】\n\n" + input.getContent());

        if (reply == null) {
            return new ChatResult(
                    "邮件任务处理失败，请重试",
                    List.of("邮件任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                    "mail"
            );
        }

        return new ChatResult(
                reply,
                List.of("邮件任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "mail"
        );
    }
}
