package com.xinl.easyclaw.base.orchestration;

import java.util.List;

/**
 * 执行计划——{@link AgentOrchestrator} 的<b>输出</b>。
 * <p>
 * 用统一结构表达两种模式，使下游执行器无需区分 single / team：
 * <ul>
 *   <li>single → 恰好一个 stage，stage 内恰好一个 step</li>
 *   <li>team   → 多个 stage 顺序执行，stage 内多个 step 并行执行</li>
 * </ul>
 * 「顺序 / 并行」的语义完全由分组结构承载：<b>stage 之间串行，stage 之内并行</b>。
 * <p>
 * 解析失败（如 workflow JSON 非法）时返回带 {@code errors} 的计划而非抛异常，
 * 便于一次性把全部问题报给用户。
 *
 * @param modeId   产出该计划的模式标识
 * @param stages   执行阶段，按顺序串行；每个阶段内的步骤并行
 * @param errors   阻断性错误，非空时不得执行
 * @param warnings 非阻断告警
 */
public record OrchestrationPlan(String modeId,
                                List<List<PlanStep>> stages,
                                List<String> errors,
                                List<String> warnings) {

    public OrchestrationPlan {
        stages = stages == null ? List.of() : List.copyOf(stages);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** single 模式的便捷构造：单阶段单步 */
    public static OrchestrationPlan single(String agentId, String instruction) {
        return new OrchestrationPlan("single",
                List.of(List.of(new PlanStep(agentId, instruction))),
                List.of(), List.of());
    }

    /** 构造一个失败计划 */
    public static OrchestrationPlan failed(String modeId, List<String> errors) {
        return new OrchestrationPlan(modeId, List.of(), errors, List.of());
    }

    /** 是否可执行（无阻断性错误且有步骤） */
    public boolean executable() {
        return errors.isEmpty() && !stages.isEmpty();
    }

    /** 总步骤数 */
    public int stepCount() {
        return stages.stream().mapToInt(List::size).sum();
    }

    /**
     * 计划中的一步：由哪个智能体执行、执行什么。
     *
     * @param agentId     执行者，须对应一个已注册的 {@code EasyClawAgent}
     * @param instruction 该步的指令，可为空（表示沿用用户原始任务）
     */
    public record PlanStep(String agentId, String instruction) {
        public PlanStep {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("PlanStep.agentId 不能为空");
            }
        }
    }
}