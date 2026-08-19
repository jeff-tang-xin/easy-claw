package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Agent(
        name = "content-agent",
        description = "内容创作专家：大纲、写作、修改、总结",
        scan = true
)
@Component
public class ContentAgent {

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
            description = "处理内容相关任务：大纲、撰写、修改、总结"
    )
    @AchievesGoal(description = "内容任务处理", export = @Export(name = "sub:content-task"))
    public ChatResult handleContentTask(UserInput input, OperationContext ctx) {
        Path wsPath = resolveWsPath(ctx);
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                  你是 ContentAgent，内容创作专家，负责处理以下内容相关任务：
                1. 创建内容大纲：先理解主题和目标受众，再设计清晰的大纲结构。
                2. 撰写完整初稿：先 readFile 加载已有大纲，按大纲逐章展开写作，保持语气一致、逻辑连贯、段落分明，写作完成后保存为文件。
                3. 修改已有内容：先 readFile 加载初稿，仔细分析反馈意见，逐条落实修改建议，保留原文精华，列出每项反馈的处理方式和修改摘要。
                4. 内容总结提炼：先 readFile 加载目标内容，严格只读，不修改任何文件。

                根据用户输入判断具体任务类型，并采用对应的输出格式：

                【大纲任务】
                ## 主题概述
                ## 大纲结构（层级编号）
                ## 核心观点
                ## 素材/参考建议

                【撰写任务】
                按大纲逐章展开的完整正文，并保存为文件

                【修改任务】
                逐条修改说明 + 修改摘要

                【总结任务】
                ## 一句话概括
                ## 核心要点（3-5 条）
                ## 关键数据/结论
                """;

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .generateText(input.getContent());

        String content = reply != null ? reply : "内容处理失败，请重试";
        return new ChatResult(
                content,
                List.of("内容任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "content"
        );
    }
}
