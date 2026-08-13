package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * 文件操作子智能体
 * <p>
 * 职责：处理所有与本地文件系统相关的任务——代码编写、文档修改、目录浏览、
 * 文件搜索（按文件名 + 按正则匹配内容）。
 * <p>
 * 工具依赖：Embabel 内置 FileTools.readWrite(workspace)。
 */
@Agent(
        name = "file-agent",
        description = "专注本地文件系统操作的 AI 智能体：读写、搜索、创建、编辑、列出目录、正则匹配内容"
)
public class FileAgent {

    private static final Logger log = LoggerFactory.getLogger(FileAgent.class);

    private final String workspaceRoot;

    public FileAgent(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Action(
            description = "理解文件操作需求并规划操作步骤",
            pre = {"user_request_available"}
    )
    public UserInput analyzeRequest(UserInput input, OperationContext context) {
        log.debug("FileAgent.analyzeRequest: {}", input.getContent());
        return input;
    }

    @Action(
            description = "执行文件系统操作：列出/读取/写入/编辑/搜索文件",
            pre = {"user_request_available"},
            post = {"file_operation_completed"},
            readOnly = false
    )
    public ChatResult executeFileTask(UserInput input, OperationContext context) {
        var fileTools = FileTools.readWrite(workspaceRoot);

        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        你是 FileAgent，一个专业的文件系统操作助手。
                        工作区根目录：%s
                        
                        可用工具：
                        - listFiles(path): 列出目录内容
                        - readFile(path): 读取文件内容（返回文本）
                        - writeFile(path, content): 创建或覆盖文件
                        - editFile(path, oldContent, newContent): 编辑文件的指定片段
                        - createDirectory(path): 创建目录
                        - delete(path): 删除文件或目录
                        - findFiles(pattern): 按 glob 模式搜索文件
                        
                        操作规范：
                        1. 编辑前先 readFile 获取当前内容
                        2. 使用 editFile 做精确的片段替换，不要用 writeFile 覆盖整个文件
                        3. 创建文件前先 createDirectory 确保父目录存在
                        4. 所有路径相对于工作区根目录
                        """.formatted(workspaceRoot))
                .withToolObject(fileTools)
                .creating(String.class)
                .fromPrompt("请完成以下文件操作任务，并用清晰的 Markdown 描述结果：\n\n"
                        + input.getContent());

        return new ChatResult(
                reply,
                List.of("文件操作: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "default"
        );
    }
}
