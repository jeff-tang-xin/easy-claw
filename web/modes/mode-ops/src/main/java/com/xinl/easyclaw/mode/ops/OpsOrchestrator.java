package com.xinl.easyclaw.mode.ops;

import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.profile.ScenarioProfile;

/**
 * 运维调度模式（工作区形态「运维」）。
 * <p>
 * 运维工作区的智能体侧与单智能体同构：一个主智能体 + {@code remote_shell} 工具
 * （每次执行都需用户确认），终端直操作不经过 LLM。计划恒为单阶段单步。
 * <p>
 * <b>modeId 红线</b>：{@code "ops"} 同时是工作区形态分类
 * （{@code WorkspaceManager.normalizeType} 白名单）与场景 mode 取值，
 * 前端 WS_TYPES / 场景种子数据（SystemDataSeeder）与之对齐；改名会三处失配。
 */
public final class OpsOrchestrator implements AgentOrchestrator {

    /**
     * 模式标识（= modeId()）。
     * <p>
     * <b>红线</b>：本常量同时是工作区形态分类（WorkspaceManager.normalizeType 动态发现）、
     * 场景种子 mode（SystemDataSeeder）、前端 WS_TYPES 的对齐锚点；改名会多处失配。
     * 后端代码引用本常量而非裸字符串，编译器保证不漂移。
     */
    public static final String MODE_ID = "ops";

    @Override
    public String modeId() {
        return MODE_ID;
    }

    /** 运维模式：主智能体只用最小工具集（remote_shell），LLM 不触达本地通用能力 */
    @Override
    public boolean minimalToolkit() {
        return true;
    }

    /**
     * 运维模式主控 = 专属运维智能体（SPI {@code OpsAgent}）。
     * <p>
     * 人格与能力声明内聚在 agent-ops 模块；场景 roleName 非空时仍以绑定为准
     * （见 {@link #resolveAgentId}）。SPI 未注册（模块缺失）时由消费方回退 main。
     */
    @Override
    public String mainAgentId() {
        // 与 agent-ops 模块 OpsAgent.AGENT_ID 对齐（mode-ops 不依赖 agent-ops，
        // 以注释锚定；改名会同时失配 SPI 注册与场景种子）
        return "ops";
    }

    /**
     * 运维模式禁止派遣子智能体：远程服务器操作是单执行体串行动作
     * （命令有先后依赖与状态耦合，并行子 Agent 无意义且放大误操作面）。
     * 主控名册整体为空，模型无从发起派遣。
     */
    @Override
    public boolean subagentDispatchEnabled() {
        return false;
    }

    /** 运维模式：不启用白名单机制 —— remote_shell 每次调用都必须用户确认 */
    @Override
    public boolean whitelistEnabled() {
        return false;
    }

    /**
     * 运维模式不要求工作流步骤：{@link #plan} 恒为单阶段单步（与 single 同构），
     * 不消费场景 workflow。若沿用「非 single 即要求」的默认，用户在场景页编辑
     * ops 场景的任意字段（如绑定主控智能体）都会被「运维模式需要至少一个工作流步骤」
     * 拒绝保存 —— 校验针对的是编排型模式的配置完整性，对单执行体模式是误伤。
     */
    @Override
    public boolean requiresWorkflowSteps() {
        return false;
    }

    @Override
    public String displayName() {
        return "运维";
    }

    /**
     * 产出单智能体执行计划（与 single 同构）。
     * <p>不覆盖 {@code execute}：接口默认实现对单阶段单步语义完全正确。
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
     * 从场景配置解析智能体标识：roleName 非空取其值，否则回退模式主控
     * {@link #mainAgentId()}（运维 = ops，其余模式 = main）。
     */
    private String resolveAgentId(ScenarioProfile scenario) {
        if (scenario == null) {
            return mainAgentId();
        }
        String roleName = scenario.getRoleName();
        return (roleName != null && !roleName.isBlank())
                ? roleName
                : mainAgentId();
    }
}
