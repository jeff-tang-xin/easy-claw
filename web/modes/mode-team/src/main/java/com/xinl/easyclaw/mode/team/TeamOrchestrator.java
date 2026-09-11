package com.xinl.easyclaw.mode.team;

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
 * 多智能体编排调度模式。
 * <p>
 * 按场景配置的 workflow 把多个智能体编排成「阶段串行、阶段内并行」的执行计划。
 * <p>
 * <b>team 只是 single 的编排包装</b>：执行单元同样是那 7 个平级智能体，
 * team 不引入新的执行物种，只多一层流程编排。
 * <p>
 * <b>派遣与编排的边界</b>：workflow 里的每一步都是「编排」；某一步的智能体在执行
 * 过程中自行派出的子智能体属于「派遣」，作为一次性任务执行、不进 workflow、
 * 只向派遣者交付结果。整个体系恒为「编排一层 + 派遣一层」。
 * <p>
 * <b>DB 兼容红线</b>：{@code modeId()} 返回 {@code "team"} 字面量，与
 * {@code ScenarioEntity.mode} 的存量取值一致，不可改名。
 */
public final class TeamOrchestrator implements AgentOrchestrator {

    /** 协调者默认智能体：场景未指定 roleName 时由 AI-CLAW 担任 */
    private static final String DEFAULT_COORDINATOR = "main";

    @Override
    public String modeId() {
        return "team";
    }

    @Override
    public String displayName() {
        return "多智能体协作";
    }

    /**
     * 把场景 workflow 翻译为执行计划。
     * <p>
     * 失败一律返回带 {@code errors} 的计划而非抛异常 —— 让调用方能一次性把
     * 全部配置问题报给用户，而不是改一个报一个。
     * <p>
     * 三种失败情形：
     * <ol>
     *   <li>场景为 null —— team 模式必须有场景配置</li>
     *   <li>workflow JSON 非法 —— 原样透传 {@code WorkflowParser} 的精确错误</li>
     *   <li>workflow 合法但无步骤 —— team 模式至少需要一步</li>
     * </ol>
     */
    @Override
    public OrchestrationPlan plan(ExecutionContext ctx) {
        ScenarioProfile scenario = ctx == null ? null : ctx.scenario();
        String task = ctx == null ? "" : ctx.task();
        if (scenario == null) {
            return OrchestrationPlan.failed(modeId(),
                    List.of("team 模式需要激活场景，当前无场景绑定"));
        }

        WorkflowParseResult parsed = WorkflowParser.parse(scenario.getWorkflow());
        if (!parsed.ok()) {
            return OrchestrationPlan.failed(modeId(), parsed.errors());
        }
        if (!parsed.hasSteps()) {
            return OrchestrationPlan.failed(modeId(),
                    List.of("team 模式需要至少一个工作流步骤"));
        }

        // 「顺序 / 并行」的语义完全由分组结构承载：stage 之间串行，stage 之内并行。
        // groupByStage 已实现该语义（parallel=true 并入上一组），此处只做结构翻译。
        List<List<WorkflowStep>> stages = WorkflowParser.groupByStage(parsed.steps());
        List<List<OrchestrationPlan.PlanStep>> planStages = new ArrayList<>(stages.size());
        for (List<WorkflowStep> stage : stages) {
            List<OrchestrationPlan.PlanStep> planStage = new ArrayList<>(stage.size());
            for (WorkflowStep step : stage) {
                // instruction 为空串时传 null，表示「沿用用户原始任务」
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
     * 渲染逻辑归编排层 {@link OrchestrationBehavior} 自持，本方法只做模式声明。
     */
    @Override
    public BehaviorSpec behaviorSpec(ScenarioProfile scenario, Set<String> availableAgentIds) {
        return OrchestrationBehavior.behaviorSpec(scenario, availableAgentIds);
    }

    /** team 场景要求执行审计：回合末校验主控自报的 {@code <orchestration-audit>} 标记 */
    @Override
    public boolean requiresExecutionAudit() {
        return true;
    }

    /**
     * 本场景的协调者智能体。
     * <p>
     * 协调者只做分发与验收，不亲自执行 workflow 里的具体步骤。
     */
    public String coordinatorAgentId(ScenarioProfile scenario) {
        if (scenario == null) {
            return DEFAULT_COORDINATOR;
        }
        String roleName = scenario.getRoleName();
        return (roleName != null && !roleName.isBlank()) ? roleName : DEFAULT_COORDINATOR;
    }
}