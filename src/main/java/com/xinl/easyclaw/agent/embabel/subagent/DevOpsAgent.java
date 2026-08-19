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
        name = "devops-agent",
        description = "DevOps 专家：CI/CD、部署、监控",
        scan = true
)
@Component
public class DevOpsAgent {

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
            description = "处理 DevOps 部署任务：CI/CD 流水线、部署、监控",
            readOnly = false
    )
    @AchievesGoal(description = "DevOps 部署任务", export = @Export(name = "sub:devops-deploy"))
    public ChatResult handleDeploy(UserInput input, OperationContext ctx) {
        Path wsPath = resolveWsPath(ctx);
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                  你是 DevOpsAgent，负责一站式处理 CI/CD 流水线、服务部署、监控设置。

                【CI/CD 流水线】
                先 listFiles 探索仓库结构和现有配置。
                根据项目技术栈选择合适的 CI 平台（GitHub Actions / GitLab CI / Jenkins）。
                生成完整的流水线 YAML 配置，包含：检出、构建、测试、打包、产物上传。
                将配置文件保存到仓库。

                【服务部署】
                先 readFile 加载流水线输出和部署配置。
                生成部署脚本或 Kubernetes 清单，包含：镜像指定、端口映射、环境变量、健康检查探针、资源限制。
                输出回滚脚本作为备份。

                【监控设置】
                先 readFile 加载部署信息和服务清单。
                监控维度：
                1. 基础指标（CPU/内存/磁盘/网络）
                2. 应用指标（QPS/延迟/错误率）
                3. 日志采集和聚合
                4. 告警规则（阈值/通知渠道）
                生成监控配置文件并保存。

                请根据用户请求的实际内容，按需执行以上一个或多个阶段，并输出执行摘要。
                """;

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .generateText("【DevOps 部署任务】\n\n" + input.getContent());

        if (reply == null || reply.isBlank()) {
            return new ChatResult(
                    "DevOps 部署任务处理失败，请重试或检查 LLM 配置。",
                    List.of("devops-deploy"),
                    "devops"
            );
        }

        return new ChatResult(reply, List.of("devops-deploy"), "devops");
    }

    @Action(
            description = "回滚到上一稳定版本",
            readOnly = false
    )
    @AchievesGoal(description = "回滚任务", export = @Export(name = "sub:devops-rollback"))
    public ChatResult handleRollback(UserInput input, OperationContext ctx) {
        Path wsPath = resolveWsPath(ctx);
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                  你是 DevOpsAgent，负责回滚操作。
                先 readFile 加载部署历史和上一稳定版本的配置。
                执行回滚步骤：
                1. 确认上一稳定版本号/镜像标签
                2. 切换流量到旧版本
                3. 验证服务健康状态
                4. 记录回滚原因和时间
                生成回滚执行报告。
                """;

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .generateText("【回滚任务】\n\n" + input.getContent());

        if (reply == null || reply.isBlank()) {
            return new ChatResult(
                    "回滚操作失败，请重试或检查 LLM 配置。",
                    List.of("devops-rollback"),
                    "devops"
            );
        }

        return new ChatResult(reply, List.of("devops-rollback"), "devops");
    }
}
