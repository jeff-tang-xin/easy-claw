package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * 代码分析子智能体
 * <p>
 * 职责：代码理解、重构建议、性能优化、Bug 分析、代码评审、代码修改。
 * <p>
 * 合并为单一 Action，去除 pre/post 链约束，避免 GOAP 重规划循环。
 * 用 generateText 替代 creating().fromPrompt()，不启动内部 GOAP。
 */
@Agent(
        name = "coding-agent",
        description = "专注代码分析与重构的 AI 智能体：代码评审、性能优化、Bug 修复、重构建议、最佳实践",
        scan = true
)
@Component
public class CodeAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAgent.class);

    private final String workspaceRoot;

    public CodeAgent(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Action(
            description = "处理代码相关任务：分析、评审、修改、重构"
    )
    @AchievesGoal(description = "代码任务处理", export = @Export(name = "sub:code-task"))
    public ChatResult handleCodeTask(UserInput input, OperationContext context) {
        log.debug("CodeAgent.handleCodeTask: {}", input.getContent());
        var fileTools = FileTools.readWrite(workspaceRoot);

        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                          你是 CodeAgent，一个专业的代码助手。
                        工作区根目录：%s

                        你可以：
                        1. 用 listFiles / findFiles 浏览项目结构
                        2. 用 readFile 阅读源代码
                        3. 用 findPatternInProject 正则搜索代码模式
                        4. 分析代码质量、性能、安全性、可维护性
                        5. 给出具体的重构建议和最佳实践
                        6. 用 editFile/writeFile 执行代码修改

                        修改规范：
                        - 编辑前先 readFile 获取当前内容
                        - 用 editFile 做精确替换，保留用户未修改的部分
                        - 创建文件前先 createDirectory 确保路径存在
                        - 修改后列出变更摘要（哪些文件改了什么）
                        - 保持原有代码风格（缩进、命名、注释风格）

                        输出规范：
                        - 用 Markdown 格式化
                        - 代码建议要有对比（before/after）
                        - 解释原理，不要只给结论
                        - 区分"必须改"和"建议改"
                        """.formatted(workspaceRoot))
                .withToolObject(fileTools)
                .withToolGroup(CoreToolGroups.MATH)
                .generateText("请处理以下代码任务：\n\n" + input.getContent());

        return new ChatResult(
                reply != null ? reply : "代码处理失败，请重试",
                List.of("代码任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "default"
        );
    }
}
