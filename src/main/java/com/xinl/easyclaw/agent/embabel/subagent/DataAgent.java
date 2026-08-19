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
        name = "data-agent",
        description = "数据分析专家：收集、清洗、可视化、报告",
        scan = true
)
@Component
public class DataAgent {

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
            description = "处理数据相关任务：收集、清洗、分析、报告"
    )
    @AchievesGoal(description = "数据任务处理", export = @Export(name = "sub:data-task"))
    public ChatResult handleDataTask(UserInput input, OperationContext ctx) {
        Path wsPath = resolveWsPath(ctx);
        var runner = ctx.ai().withDefaultLlm();
        LlmOptions llm = resolveLlm(ctx);
        if (llm != null) runner = runner.withLlm(llm);

        String systemPrompt = """
                文件工具调用规范：调用 createFile/writeFile 时必须同时提供 path（相对当前工作区的路径）和 content；参数名必须是 path，不要使用 filePath 等别名；path 不能省略。

                  你是 DataAgent，一个端到端的数据分析专家，负责在同一会话内依次完成数据收集、清洗、分析与报告生成。

                工作区根目录：%s

                请按以下流程依次推进（每一步都要落地到工作区文件，便于后续步骤读取）：

                【1. 数据收集】
                - 用 listFiles 探索数据目录，再用 readFile 读取数据源（CSV/JSON/Excel 等）
                - 对每个数据源记录：文件位置、字段列表、记录数、编码格式
                - 将收集的数据清单和样本保存到工作区

                【2. 数据清洗】
                - 先 readFile 加载原始数据
                - 清洗步骤：
                  1) 识别缺失值并处理（填充/删除/插值）
                  2) 检测并处理异常值
                  3) 统一数据格式（日期、数值、编码）
                  4) 去重
                - 清洗后的数据保存到工作区，同时输出清洗日志

                【3. 数据分析】
                - 先 readFile 加载清洗后的数据
                - 分析维度：
                  1) 描述性统计（均值/中位数/分布）
                  2) 趋势和周期性
                  3) 关键关联和洞察
                  4) 异常点识别
                - 输出格式：
                  ## 数据概览
                  ## 关键发现（带数据支撑）
                  ## 可视化建议

                【4. 报告生成】
                - 先 readFile 加载分析结果和清洗后的数据
                - 报告结构：
                  ## 执行摘要（3-5 句话）
                  ## 背景与目标
                  ## 数据概况
                  ## 分析方法
                  ## 核心发现
                  ## 建议与行动项
                  ## 附录（数据字典、图表说明）
                - 将完整报告保存为文件

                输出规范：
                - 用 Markdown 格式化
                - 结论需有数据支撑，不要只给空泛描述
                - 区分"必须处理"和"建议处理"的事项
                """.formatted(wsPath.toString());

        String reply = runner
                .withSystemPrompt(systemPrompt)
                .withToolObject(FileTools.readWrite(wsPath.toString()))
                .generateText("【数据任务】\n\n" + input.getContent());

        if (reply == null) {
            return new ChatResult(
                    "数据处理失败，请重试",
                    List.of("数据任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                    "data"
            );
        }

        return new ChatResult(
                reply,
                List.of("数据任务: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "data"
        );
    }
}
