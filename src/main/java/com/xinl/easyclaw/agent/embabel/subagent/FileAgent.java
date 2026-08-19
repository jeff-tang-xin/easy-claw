package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
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

/**
 * 文件系统子智能体
 * <p>
 * 职责：文件搜索、整理归档、批量编辑。
 * <p>
 * 合并为单一 Action，去除 pre/post 链约束，避免 GOAP 重规划循环。
 * 用 generateText 替代 creating().fromPrompt()，不启动内部 GOAP。
 */
@Agent(
        name = "file-agent",
        description = "文件系统专家：搜索、整理、批量操作、格式转换",
        scan = true
)
@Component
public class FileAgent {

    private static final Logger log = LoggerFactory.getLogger(FileAgent.class);

    private final SkillGoalFactory skillGoalFactory;

    public FileAgent(SkillGoalFactory skillGoalFactory) {
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
            description = "处理文件相关任务：搜索、整理、批量编辑"
    )
    @AchievesGoal(description = "文件任务处理", export = @Export(name = "sub:file-task"))
    public ChatResult handleFileTask(UserInput input, OperationContext ctx) {
        log.debug("FileAgent.handleFileTask: {}", input.getContent());
        Path wsPath = resolveWsPath(ctx);

        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                你是 FileAgent，文件系统专家，负责处理文件相关任务：搜索、整理、批量编辑。

                工作区根目录：%s

                你可以处理以下类型的任务：

                1. 搜索任务：
                   - 先用 listFiles 探索目录结构
                   - 再用 grepFiles 搜索内容
                   - 列出所有匹配结果，说明每个文件的作用

                2. 整理任务：
                   - 执行前先探索当前目录结构
                   - 所有变更用 createDirectory + 移动/重命名完成
                   - 列出每步操作和最终目录结构

                3. 批量编辑任务：
                   - 对每个目标文件：先 readFile → editFile → 验证修改正确
                   - 列出所有文件的变更摘要

                输出规范：
                - 用 Markdown 格式化
                - 区分任务类型，说明每步操作
                - 给出变更摘要
                """.formatted(wsPath) + buildSkillContext(ctx);

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .generateText("【文件任务】\n\n" + input.getContent());

        return new ChatResult(
                reply != null ? reply : "文件处理失败，请重试",
                List.of("文件任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "file"
        );
    }
}
