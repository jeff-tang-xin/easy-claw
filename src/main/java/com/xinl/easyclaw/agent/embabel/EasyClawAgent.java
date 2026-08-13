package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.tools.file.FileTools;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.UserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Agent(
        description = "Easy-Claw 核心智能体：理解用户意图，规划步骤，调用文件/网络/数学工具完成任务，返回结构化结果"
)
public class EasyClawAgent {

    private static final Logger log = LoggerFactory.getLogger(EasyClawAgent.class);

    private final String workspaceRoot;
    private final boolean enableWebSearch;

    public EasyClawAgent(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot,
            @Value("${easy-claw.tools.web.enabled:true}") boolean enableWebSearch) {
        this.workspaceRoot = workspaceRoot;
        this.enableWebSearch = enableWebSearch;
        log.info("EasyClawAgent 初始化: workspaceRoot={}, webSearch={}", workspaceRoot, enableWebSearch);
    }

    @Action(description = "解析用户输入，提取意图、目标工作区和偏好设置")
    public UserInput parseRequest(UserRequest request, OperationContext context) {
        log.debug("解析用户请求: {}", request.content());
        return new UserInput(request.content());
    }

    @Action(description = "执行需要读写本地文件的任务，如代码编写、文档修改、目录浏览等")
    public ChatResult handleFileTask(UserInput input, OperationContext context) {
        var fileTools = FileTools.readWrite(resolveWorkspace(requestWorkspace(input)));

        return context.ai()
                .withDefaultLlm()
                .withSystemPrompt("你是 Easy-Claw，一个专注于文件操作的 AI 助手。"
                        + "可以创建、读取、编辑、删除、搜索文件和目录。"
                        + "操作前请先 listFiles 确认路径，编辑前请先 readFile 获取当前内容。"
                        + "所有路径相对于工作区根目录。")
                .withToolObject(fileTools)
                .creating(ChatResult.class)
                .fromPrompt("请使用文件工具完成以下任务，并返回 ChatResult 格式的回复：\n\n"
                        + input.getContent());
    }

    @Action(description = "执行需要联网搜索、抓取网页的任务")
    public ChatResult handleWebTask(UserInput input, OperationContext context) {
        if (!enableWebSearch) {
            return new ChatResult(
                    "网络搜索功能未启用。请设置 easy-claw.tools.web.enabled=true 并配置 Brave Search API Key。",
                    List.of("web-search-disabled"),
                    "default"
            );
        }

        return context.ai()
                .withDefaultLlm()
                .withSystemPrompt("你是 Easy-Claw，一个擅长信息检索的 AI 助手。"
                        + "使用网络搜索工具查找最新、最准确的信息，注明来源。")
                .withToolGroup(CoreToolGroups.WEB)
                .creating(ChatResult.class)
                .fromPrompt("请使用网络搜索工具回答以下问题，并返回 ChatResult 格式的回复：\n\n"
                        + input.getContent());
    }

    @Action(description = "执行通用对话任务，可根据需要调用文件、网络、数学等任意工具")
    @AchievesGoal(description = "完成用户请求，返回最终结果")
    public ChatResult handleGeneralTask(UserInput input, OperationContext context) {
        var fileTools = FileTools.readWrite(resolveWorkspace(requestWorkspace(input)));
        var promptRunner = context.ai()
                .withDefaultLlm()
                .withSystemPrompt(buildSystemPrompt());

        promptRunner = promptRunner.withToolObject(fileTools);
        if (enableWebSearch) {
            promptRunner = promptRunner.withToolGroup(CoreToolGroups.WEB);
        }
        promptRunner = promptRunner.withToolGroup(CoreToolGroups.MATH);

        List<String> steps = new ArrayList<>();
        steps.add("理解用户意图");
        if (input.getContent().contains("文件") || input.getContent().contains("目录")
                || input.getContent().contains("代码") || input.getContent().contains("保存")
                || input.getContent().contains("创建") || input.getContent().contains("编辑")) {
            steps.add("操作工作区文件系统");
        }
        if (input.getContent().contains("搜索") || input.getContent().contains("查找")
                || input.getContent().contains("最新") || input.getContent().contains("网页")) {
            steps.add("联网搜索信息");
        }
        steps.add("生成回复");

        ChatResult result = promptRunner
                .creating(ChatResult.class)
                .fromPrompt(buildTaskPrompt(input));

        return new ChatResult(
                result.reply(),
                steps,
                result.modelUsed() != null ? result.modelUsed() : "default"
        );
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Easy-Claw，一个全能 AI 工作助手。\n\n");
        sb.append("你拥有以下能力：\n");
        sb.append("- 📁 文件操作：列出、读取、写入、编辑、搜索工作区内的文件\n");
        if (enableWebSearch) {
            sb.append("- 🌐 网络搜索：查找最新信息、抓取网页内容\n");
        }
        sb.append("- 🔢 数学计算：执行各种数学运算\n\n");
        sb.append("工作区根目录：").append(workspaceRoot).append("\n\n");
        sb.append("回复规则：\n");
        sb.append("1. 直接回答用户问题，不要暴露内部工具调用细节\n");
        sb.append("2. 使用 Markdown 格式化输出\n");
        sb.append("3. 引用信息时注明来源\n");
        sb.append("4. 文件操作前先确认路径，编辑前先读取当前内容\n");
        return sb.toString();
    }

    private String buildTaskPrompt(UserInput input) {
        return """
                用户请求：
                %s

                请分析需求，选择合适的工具完成任务，然后返回 ChatResult：
                - reply：对用户友好的回答（Markdown 格式）
                - steps：已执行的步骤名称列表
                - modelUsed：使用的模型名称（可填 "default"）
                """.formatted(input.getContent());
    }

    private String resolveWorkspace(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return Path.of(workspaceRoot, workspaceId).toString();
        }
        return workspaceRoot;
    }

    private String requestWorkspace(UserInput input) {
        return null;
    }
}
