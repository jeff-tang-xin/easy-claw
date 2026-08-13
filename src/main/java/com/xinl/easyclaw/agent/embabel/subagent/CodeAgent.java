package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * 代码分析子智能体
 * <p>
 * 职责：代码理解、重构建议、性能优化、Bug 分析、代码评审。
 * 工作方式：先 readFile 拿到源代码，然后用 LLM 分析，最后用 editFile/writeFile 应用修改。
 * <p>
 * 工具依赖：FileTools.readWrite + CoreToolGroups.MATH（性能计算公式）。
 */
@Agent(
        name = "code-agent",
        description = "专注代码分析与重构的 AI 智能体：代码评审、性能优化、Bug 修复、重构建议、最佳实践"
)
public class CodeAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAgent.class);

    private final String workspaceRoot;

    public CodeAgent(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Action(
            description = "分析代码相关需求，理解上下文",
            pre = {"user_request_available"}
    )
    public UserInput understandCodeTask(UserInput input, OperationContext context) {
        log.debug("CodeAgent.understandCodeTask: {}", input.getContent());
        return input;
    }

    @Action(
            description = "分析现有代码，给出评审、优化或重构建议",
            pre = {"user_request_available"},
            post = {"code_analyzed"},
            readOnly = true
    )
    public ChatResult analyzeCodebase(UserInput input, OperationContext context) {
        var fileTools = FileTools.readOnly(workspaceRoot);

        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        你是 CodeAgent，一个专业的代码分析助手。
                        
                        你可以：
                        1. 用 listFiles / findFiles 浏览项目结构
                        2. 用 readFile 阅读源代码
                        3. 用 findPatternInProject 正则搜索代码模式
                        4. 分析代码质量、性能、安全性、可维护性
                        5. 给出具体的重构建议和最佳实践
                        
                        输出规范：
                        - 用 Markdown 格式化
                        - 代码建议要有对比（before/after）
                        - 解释原理，不要只给结论
                        - 区分"必须改"和"建议改"
                        """)
                .withToolObject(fileTools)
                .withToolGroup(CoreToolGroups.MATH)
                .creating(String.class)
                .fromPrompt("请分析并给出建议：\n\n" + input.getContent());

        return new ChatResult(
                reply,
                List.of("代码分析: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "default"
        );
    }

    @Action(
            description = "执行代码修改：创建、编辑、重构文件",
            pre = {"user_request_available"},
            post = {"code_modified"},
            readOnly = false
    )
    public ChatResult modifyCode(UserInput input, OperationContext context) {
        var fileTools = FileTools.readWrite(workspaceRoot);

        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        你是 CodeAgent，一个专业的代码修改助手。
                        工作区根目录：%s
                        
                        修改规范：
                        1. 编辑前先 readFile 获取当前内容
                        2. 用 editFile 做精确替换，保留用户未修改的部分
                        3. 创建文件前先 createDirectory 确保路径存在
                        4. 修改后列出变更摘要（哪些文件改了什么）
                        5. 保持原有代码风格（缩进、命名、注释风格）
                        """.formatted(workspaceRoot))
                .withToolObject(fileTools)
                .creating(String.class)
                .fromPrompt("请执行以下代码修改任务：\n\n" + input.getContent());

        return new ChatResult(
                reply,
                List.of("代码修改: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "default"
        );
    }
}
