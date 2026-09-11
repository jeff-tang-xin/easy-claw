package com.xinl.easyclaw.mode.schedule;

import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.BehaviorSpec;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationBehavior;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
import com.xinl.easyclaw.base.workflow.WorkflowParseResult;
import com.xinl.easyclaw.base.workflow.WorkflowParser;
import com.xinl.easyclaw.base.workflow.WorkflowStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 定时 workflow 调度模式。
 * <p>
 * <b>本模式与 team 的唯一差别是「触发方式」而非「执行结构」</b>：
 * team 由用户会话触发，schedule 由 cron 定时触发；但执行计划的结构完全相同——
 * 同样按场景 workflow 解析为「阶段串行、阶段内并行」的计划。
 * <p>
 * 这意味着 schedule 模式可以复用 team 模式的所有执行器逻辑，只替换触发层。
 * 如果未来 team 模式增加了新特性（如条件分支、循环），schedule 默认自动继承。
 * <p>
 * <b>触发与编排的分离</b>：本模式只负责编排计划的生成，不负责定时调度。
 * 真正的 {@code @Scheduled} / {@code TaskScheduler} 注册在 api 层完成，
 * 本模块保持零 Spring 依赖。
 * <p>
 * <b>DB 兼容红线</b>：{@code modeId()} 返回 {@code "schedule"} 字面量。
 * 存量场景的 mode 字段当前为 {@code "single"} / {@code "team"}，新增 schedule
 * 场景不会影响历史数据。
 */
public final class ScheduleOrchestrator implements AgentOrchestrator {

    @Override
    public String modeId() {
        return "schedule";
    }

    @Override
    public String displayName() {
        return "定时任务";
    }

    /**
     * 把场景 workflow 翻译为执行计划。
     * <p>
     * 逻辑与 {@code TeamOrchestrator.plan()} 基本一致，差别在于：
     * <ul>
     *   <li>schedule 模式允许 workflow 只有一步（定时任务通常只需一个智能体）</li>
     *   <li>也允许多步（复杂定时流程可编排多个智能体）</li>
     *   <li>空步骤检查仍保留，因为无步骤的计划毫无意义</li>
     * </ul>
     * <p>
     * 失败一律返回带 {@code errors} 的计划而非抛异常 —— 让调用方能一次性把
     * 全部配置问题报给用户，而不是改一个报一个。
     * <p>
     * 三种失败情形：
     * <ol>
     *   <li>场景为 null —— schedule 模式必须有场景配置</li>
     *   <li>workflow JSON 非法 —— 原样透传 {@code WorkflowParser} 的精确错误</li>
     *   <li>workflow 合法但无步骤 —— 空计划无法执行</li>
     * </ol>
     */
    @Override
    public OrchestrationPlan plan(ExecutionContext ctx) {
        ScenarioProfile scenario = ctx == null ? null : ctx.scenario();
        String task = ctx == null ? "" : ctx.task();
        if (scenario == null) {
            return OrchestrationPlan.failed(modeId(),
                    List.of("schedule 模式需要激活场景，当前无场景绑定"));
        }

        WorkflowParseResult parsed = WorkflowParser.parse(scenario.getWorkflow());
        if (!parsed.ok()) {
            return OrchestrationPlan.failed(modeId(), parsed.errors());
        }
        if (!parsed.hasSteps()) {
            return OrchestrationPlan.failed(modeId(),
                    List.of("schedule 模式需要至少一个工作流步骤"));
        }

        // 「顺序 / 并行」的语义完全由分组结构承载：stage 之间串行，stage 之内并行。
        // groupByStage 已实现该语义（parallel=true 并入上一组），此处只做结构翻译。
        List<List<WorkflowStep>> stages = WorkflowParser.groupByStage(parsed.steps());
        List<List<OrchestrationPlan.PlanStep>> planStages = new ArrayList<>(stages.size());
        for (List<WorkflowStep> stage : stages) {
            List<OrchestrationPlan.PlanStep> planStage = new ArrayList<>(stage.size());
            for (WorkflowStep step : stage) {
                // instruction 为空串时传 task 兜底，确保定时任务永远有明确的执行指令
                String instruction = step.instruction().isBlank() ? task : step.instruction();
                planStage.add(new OrchestrationPlan.PlanStep(step.agentId(), instruction));
            }
            planStages.add(planStage);
        }

        return new OrchestrationPlan(modeId(), planStages, List.of(), parsed.warnings());
    }

    /**
     * 编排行为规范（协调者纪律 + 验收标准 + 审计要求）。
     * <p>
     * 与 team 同源：执行结构相同的模式共用同一份行为规范（渲染逻辑归编排层
     * {@link OrchestrationBehavior} 自持）；未来需要差异化时在本方法内分支。
     */
    @Override
    public BehaviorSpec behaviorSpec(ScenarioProfile scenario, Set<String> availableAgentIds) {
        return OrchestrationBehavior.behaviorSpec(scenario, availableAgentIds);
    }

    /** schedule 场景同样要求执行审计：回合末校验主控自报的 {@code <orchestration-audit>} 标记 */
    @Override
    public boolean requiresExecutionAudit() {
        return true;
    }
}