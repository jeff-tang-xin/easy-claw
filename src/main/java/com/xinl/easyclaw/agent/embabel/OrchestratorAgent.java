package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.tool.Subagent;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.subagent.CodeAgent;
import com.xinl.easyclaw.agent.embabel.subagent.FileAgent;
import com.xinl.easyclaw.agent.embabel.subagent.WebAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排智能体（Orchestrator）
 * <p>
 * 职责：理解用户意图，智能选择最适合的子 Agent，协调多个子 Agent 协作完成复杂任务。
 * <p>
 * 设计要点：
 * - 通过 {@code Subagent.ofClass(XxxAgent.class).build()} 把 FileAgent/WebAgent/CodeAgent 挂载为工具
 * - 这让 GOAP planner 能根据子 Agent 的 pre/post 条件自动规划调用顺序
 * - 主编排器自己也有文件 + 网络 + 数学工具可直接使用（快速简单任务不一定要委托）
 * <p>
 * 工具层次：
 * <pre>
 * OrchestratorAgent
 *   ├─ Subagent(FileAgent)    → 文件操作
 *   ├─ Subagent(WebAgent)     → 网络搜索
 *   ├─ Subagent(CodeAgent)    → 代码分析/修改
 *   ├─ FileTools.readWrite()  → 直接文件操作（简单任务）
 *   ├─ CoreToolGroups.WEB     → 直接网络搜索（简单查询）
 *   └─ CoreToolGroups.MATH    → 数学计算
 * </pre>
 */
@Agent(
        name = "easy-claw-orchestrator",
        description = "Easy-Claw 主编排智能体：理解意图、选择子 Agent、协调协作、汇总结果"
)
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    private final String workspaceRoot;
    private final boolean enableWeb;

    public OrchestratorAgent(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot,
            @Value("${easy-claw.tools.web.enabled:true}") boolean enableWeb) {
        this.workspaceRoot = workspaceRoot;
        this.enableWeb = enableWeb;
        log.info("OrchestratorAgent 初始化: workspaceRoot={}, web={}", workspaceRoot, enableWeb);
    }

    @Action(
            description = "理解用户请求，识别意图类型（文件操作 / 网络搜索 / 代码分析 / 通用对话）",
            pre = {"user_request_available"},
            post = {"intent_analyzed"}
    )
    public UserInput analyzeIntent(UserInput input, OperationContext context) {
        log.debug("OrchestratorAgent.analyzeIntent: {}", input.getContent());
        return input;
    }

    @Action(
            description = "通用对话：直接回答，不委托子 Agent，适合闲聊、概念解释、简单问答",
            pre = {"intent_analyzed"},
            post = {"goal_achieved"},
            readOnly = true
    )
    public ChatResult directChat(UserInput input, OperationContext context) {
        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        你是 Easy-Claw 智能助手。用友好、专业、简洁的方式回答问题。
                        使用 Markdown 格式化输出。回答前先思考。
                        """)
                .creating(String.class)
                .fromPrompt(input.getContent());

        return new ChatResult(reply, List.of("直接回答"), "default");
    }

    @Action(
            description = "编排子 Agent 协作完成复杂任务：根据意图调用合适的子 Agent 并汇总结果",
            pre = {"intent_analyzed"},
            post = {"goal_achieved"}
    )
    @AchievesGoal(description = "完成用户请求")
    public ChatResult orchestrate(UserInput input, OperationContext context) {
        var promptRunner = context.ai()
                .withDefaultLlm()
                .withSystemPrompt(buildSystemPrompt(input));

        promptRunner = promptRunner
                .withToolObject(Subagent.ofClass(FileAgent.class).consuming(UserInput.class))
                .withToolObject(Subagent.ofClass(WebAgent.class).consuming(UserInput.class))
                .withToolObject(Subagent.ofClass(CodeAgent.class).consuming(UserInput.class));

        var fileTools = com.embabel.agent.tools.file.FileTools.readWrite(workspaceRoot);
        promptRunner = promptRunner.withToolObject(fileTools);
        if (enableWeb) {
            promptRunner = promptRunner.withToolGroup(CoreToolGroups.WEB);
        }
        promptRunner = promptRunner.withToolGroup(CoreToolGroups.MATH);

        String reply = promptRunner
                .creating(String.class)
                .fromPrompt(buildTaskPrompt(input));

        List<String> steps = new ArrayList<>();
        steps.add("意图分析");
        steps.add("子 Agent 编排");
        steps.add("结果汇总");

        return new ChatResult(reply, steps, "default");
    }

    private String buildSystemPrompt(UserInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Easy-Claw 主编排智能体。\n\n");
        sb.append("可用子 Agent（选择最合适的一个或多个）：\n");
        sb.append("- **file-agent**: 文件操作（读写、搜索、编辑、目录管理）\n");
        sb.append("- **web-agent**: 网络搜索和网页抓取，获取最新信息\n");
        sb.append("- **code-agent**: 代码分析、评审、重构、Bug 修复\n\n");
        sb.append("通用工具：\n");
        sb.append("- 文件系统工具（直接可用）\n");
        if (enableWeb) sb.append("- 网络搜索工具（直接可用）\n");
        sb.append("- 数学计算工具\n\n");
        sb.append("工作区根目录：").append(workspaceRoot).append("\n\n");
        sb.append("编排策略：\n");
        sb.append("1. 简单文件操作 → 直接用文件工具\n");
        sb.append("2. 简单搜索 → 直接用网络工具\n");
        sb.append("3. 复杂任务 → 委托给专业子 Agent\n");
        sb.append("4. 多步骤任务 → 依次调用多个子 Agent\n\n");
        sb.append("输出：用 Markdown 友好地回答用户，不要暴露工具调用细节。");
        return sb.toString();
    }

    private String buildTaskPrompt(UserInput input) {
        return """
                用户请求：
                %s

                请选择合适的工具或子 Agent 完成任务，然后用 Markdown 给出最终回答。
                """.formatted(input.getContent());
    }
}
