package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.common.ai.model.LlmOptions;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import com.xinl.easyclaw.tools.shell.ShellTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 验证子智能体
 * <p>
 * 职责：代码修改后的质量验证 — 编译检查、测试运行、生成质量报告。
 * <p>
 * 合并为单一 Action，去除 pre/post 链约束，避免 GOAP 重规划循环。
 * 用 generateText 替代 creating().fromPrompt()，不启动内部 GOAP。
 * <p>
 * 工具依赖：ShellTools（执行 mvn compile / mvn test）。
 */
@Agent(
        name = "verify-agent",
        description = "质量验证智能体：编译检查、测试运行、部署验证、生成验证报告",
        scan = true
)
@Component
public class VerifierAgent {

    private static final Logger log = LoggerFactory.getLogger(VerifierAgent.class);

    private final ShellTools shellTools;

    public VerifierAgent(ShellTools shellTools) {
        this.shellTools = shellTools;
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

    private String resolveProjectPath(OperationContext ctx) {
        WorkspaceContextData d = ctx.last(WorkspaceContextData.class);
        if (d != null && d.workspacePath() != null) {
            Path p = d.workspacePath();
            return p.isAbsolute() ? "." : p.toString();
        }
        return ".";
    }

    /**
     * 处理验证任务：在一个 Action 内完成编译检查、测试运行和质量报告生成。
     * 合并原 verifyCompile / verifyTests / generateVerifyReport 三个方法，
     * 去除 pre/post 链约束，使用 generateText 单轮 LLM 调用。
     */
    @Action(
            description = "处理验证任务：编译检查、测试运行、质量报告"
    )
    @AchievesGoal(description = "验证任务处理", export = @Export(name = "sub:verify-task"))
    public ChatResult handleVerifyTask(UserInput input, OperationContext ctx) {
        String projectPath = resolveProjectPath(ctx);
        log.info("VerifierAgent.handleVerifyTask: projectPath={}", projectPath);

        // ====== 编译验证 ======
        String compileResult = shellTools.verifyMavenCompile(projectPath);
        boolean compilePassed = compileResult.contains("✅");

        // ====== 测试验证 ======
        String testResult = shellTools.runMavenTests(projectPath);
        boolean testsPassed = testResult.contains("✅");

        // ====== 合并后的 system prompt：编译 + 测试 + 质量报告 ======
        String systemPrompt = """
                你是 VerifierAgent，负责完整的质量验证流程：编译检查、测试运行、生成质量报告。
                本次会一次性收到 `mvn compile` 与 `mvn test` 的输出，请综合分析并产出最终报告。

                【编译验证分析】
                1. 如果编译通过，确认修改有效，列出可能的隐患（如 deprecation 警告）
                2. 如果编译失败，定位错误文件和行号，给出具体修复建议
                3. 区分"语法错误"和"依赖缺失"等不同类型的问题

                【测试验证分析】
                1. 统计通过/失败/跳过的用例数
                2. 失败用例定位到具体的测试方法和断言
                3. 区分"实现错误"和"测试本身过时"等不同情况
                4. 给出修复建议（修改实现 / 修改测试 / 跳过测试）

                【整体质量评估】
                基于编译和测试结果，给出风险点和是否可合并/部署的判断。

                输出格式：
                ## 整体质量评估
                ## 编译状态（通过/失败）
                ## 编译问题清单（如有）
                ## 编译修复建议（如有）
                ## 测试结果（通过数/失败数/跳过数）
                ## 失败用例分析（如有）
                ## 测试修复建议
                ## 风险点和建议
                ## 是否可以合并/部署（是/否/有条件）
                """;

        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .generateText("原始任务: " + input.getContent()
                        + "\n\n项目路径: " + projectPath
                        + "\n\n编译输出:\n" + compileResult
                        + "\n\n测试输出:\n" + testResult
                        + "\n\n请基于以上编译和测试结果生成完整的验证报告。");

        if (reply == null) {
            return new ChatResult(
                    "验证任务处理失败：LLM 未返回结果，请重试。",
                    List.of("验证任务: 失败"),
                    "verifier"
            );
        }

        String status = (compilePassed && testsPassed) ? "全部通过" : "存在失败";
        return new ChatResult(
                reply,
                List.of("验证任务: " + status),
                "verifier"
        );
    }
}
