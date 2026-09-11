package com.xinl.easyclaw.mode.single;

import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.profile.ScenarioProfile;

/**
 * 单智能体调度模式。
 * <p>
 * 用户在场景中自选一个智能体，或使用默认的 AI-CLAW 通用智能体。
 * 计划恒为单阶段单步 —— 执行器无需区分 single / team，按统一结构执行即可。
 * <p>
 * <b>DB 兼容红线</b>：{@code modeId()} 返回 {@code "single"} 字面量。
 * {@code ScenarioEntity.mode} 存量数据就是 {@code "single"}，SQLite
 * {@code ddl-auto: update} 不迁移历史值，改名字面量会直接导致存量场景无法匹配。
 */
public final class SingleOrchestrator implements AgentOrchestrator {

    /** 默认智能体：场景未绑定具体智能体时使用 AI-CLAW 主控智能体 */
    private static final String DEFAULT_AGENT_ID = "main";

    @Override
    public String modeId() {
        return "single";
    }

    @Override
    public String displayName() {
        return "单智能体";
    }

    /**
     * 产出单智能体执行计划。
     * <p>
     * 逻辑简单：用户选了一个智能体（或默认 main），加上本次任务指令，一步完成。
     * <p>
     * <b>不覆盖 {@code execute}</b>：接口默认实现（stage 顺序执行、任一步失败即中止）
     * 对单阶段单步语义完全正确，覆盖只会写出一份等价代码。
     *
     * @param ctx 执行上下文，其 {@code scenario} 可为 null（无场景绑定），
     *            此时回退到默认 {@code main} 智能体
     * @return 单阶段单步计划
     */
    @Override
    public OrchestrationPlan plan(ExecutionContext ctx) {
        String agentId = resolveAgentId(ctx == null ? null : ctx.scenario());
        return OrchestrationPlan.single(agentId, ctx == null ? "" : ctx.task());
    }

    /**
     * 从场景配置解析智能体标识。
     * <p>
     * 解析策略（按优先级）：
     * <ol>
     *   <li>场景的 {@code roleName} 非空 → 取其值作为 agentId</li>
     *   <li>场景为 null / roleName 为空 → 回退 {@code main}</li>
     * </ol>
     */
    private String resolveAgentId(ScenarioProfile scenario) {
        if (scenario == null) {
            return DEFAULT_AGENT_ID;
        }
        String roleName = scenario.getRoleName();
        return (roleName != null && !roleName.isBlank())
                ? roleName
                : DEFAULT_AGENT_ID;
    }
}