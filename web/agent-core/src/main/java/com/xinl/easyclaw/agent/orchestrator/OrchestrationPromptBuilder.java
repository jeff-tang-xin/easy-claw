package com.xinl.easyclaw.agent.orchestrator;

import com.xinl.easyclaw.base.orchestration.BehaviorSpec;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景提示构建器
 * <p>
 * 把激活的 {@link ScenarioProfile}（场景提示词 + 能力边界）翻译成注入主智能体
 * system prompt 的场景段；编排型模式（team / schedule）的<b>行为规范与门禁要求</b>
 * 不在本类 —— 那是模式自身的知识，由编排层各模式通过
 * {@code AgentOrchestrator#behaviorSpec} 自行声明（渲染逻辑见 base 编排层的
 * {@code OrchestrationBehavior}），本类只做委托装配：
 * <ul>
 *   <li>single 模式：只注入场景人格/规范提示词</li>
 *   <li>team 模式：在场景提示词之上，追加模式声明的编排行为规范（协调者定位、
 *       验收纪律、审计要求）。主智能体在此模式下是<b>常驻协调者</b>——只做分发与验收，
 *       决定「质量（合格否）」与「走向（下一阶段 / 返工 / 调整）」，不亲自执行具体任务</li>
 * </ul>
 * <b>编排单位是智能体</b>：每个 SPI 智能体自带人格与模型，是完整的执行单元；同一阶段的 N 个智能体
 * 应被同时激活为 N 路并发调用。子 Agent 只是承载智能体运行的载体（同名声明即该智能体的
 * 执行体），属于实现细节，不进场景配置。
 * <p>
 * team 模式的行为规范会要求主智能体在回复末尾输出 {@code <orchestration-audit .../>}
 * 审计行，供 base 编排层的 {@code OrchestrationAuditVerifier} 校验「计划是否真的被执行」。
 */
public final class OrchestrationPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationPromptBuilder.class);

    private OrchestrationPromptBuilder() {
    }

    /**
     * 构建场景 augment（场景未激活或内容为空时返回 null）
     * <p>
     * 输出固定三段式，对应「场景决定你处在什么环境里、什么能做什么不能做、该怎么做」：
     * <ol>
     *   <li><b>环境</b>——场景描述</li>
     *   <li><b>能力边界</b>——绑定的 skill / 子 Agent / MCP（软边界，见 {@code capabilityBoundary}）</li>
     *   <li><b>方法论</b>——场景提示词 + 协作方式（single 单智能体 / team 多智能体协作）</li>
     * </ol>
     * 能力边界原先由 {@code WorkspaceAgentBuilder} 单独拼在场景块之外，模型看到的是
     * 两段不相干的话；并入这里后「环境-边界-方法」在同一标题下形成完整语义。
     *
     * @param scenario   激活的场景（可为 null）
     * @param subagents  当前 Agent 已注册的子 Agent 声明（用于成员校验）
     * @param boundary   场景能力边界文本，可为 null；由调用方按绑定关系渲染
     */
    public static String build(ScenarioProfile scenario, List<SubagentDeclaration> subagents,
                               String boundary) {
        if (scenario == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 🎬 当前场景：").append(displayName(scenario)).append("\n");
        if (notBlank(scenario.getDescription())) {
            sb.append("\n### 你所处的环境\n").append(scenario.getDescription().trim()).append("\n");
        }
        if (notBlank(boundary)) {
            sb.append("\n### 能力边界\n").append(boundary.trim()).append("\n");
        }
        sb.append("\n### 工作方法论（").append(methodologyLabel(scenario)).append("）\n");
        int beforeMethod = sb.length();
        if (notBlank(scenario.getSystemPrompt())) {
            sb.append(scenario.getSystemPrompt().trim()).append("\n");
        }
        if (OrchestrationModes.isOrchestrated(scenario.getMode())) {
            String orchestration = behaviorSpecOf(scenario, subagents);
            if (orchestration != null) {
                sb.append("\n").append(orchestration);
            }
        }
        if (sb.length() == beforeMethod) {
            // 方法论段无内容：留一个空标题反而是噪声，回退为「按通用工作方式执行」
            sb.append("本场景未定义专属方法论，按基座的任务闭环协议执行。\n");
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * 向编排层取本模式声明的行为规范；渲染告警在此落日志（base 契约层零日志依赖）。
     */
    private static String behaviorSpecOf(ScenarioProfile scenario, List<SubagentDeclaration> subagents) {
        Set<String> available = new LinkedHashSet<>();
        for (SubagentDeclaration d : subagents) {
            available.add(d.getName());
        }
        BehaviorSpec spec = OrchestrationModes.find(scenario.getMode())
                .map(o -> o.behaviorSpec(scenario, available))
                .orElse(null);
        if (spec == null) {
            return null;
        }
        for (String warning : spec.warnings()) {
            log.warn("{}", warning);
        }
        return spec.prompt();
    }

    /**
     * 方法论标签：把 mode 翻译成模型能理解的协作方式，而不是内部枚举值。
     * <p>
     * 翻译表由各模式自己通过 {@code AgentOrchestrator#displayName()} 声明 ——
     * 这属于模式自身的知识，不该在渲染方维护一张会漏改的 switch。
     */
    private static String methodologyLabel(ScenarioProfile scenario) {
        return OrchestrationModes.displayNameOf(scenario.getMode());
    }

    private static String displayName(ScenarioProfile scenario) {
        if (notBlank(scenario.getDisplayName())) {
            return notBlank(scenario.getIcon())
                    ? scenario.getIcon() + " " + scenario.getDisplayName()
                    : scenario.getDisplayName();
        }
        return scenario.getName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
