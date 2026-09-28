package com.xinl.easyclaw.base.orchestration;

import com.xinl.easyclaw.base.profile.ScenarioProfile;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体调度器 SPI。
 * <p>
 * <b>调度器不是智能体</b>——它只决定「这次任务派哪些智能体、以什么顺序跑、
 * 产出合格与否」，执行单元始终是实现了 {@code EasyClawAgent} 的那 7 个平级智能体。
 * <p>
 * <b>职责边界（重要）</b>：
 * <ul>
 *   <li><b>本接口负责编排语义</b>——步骤顺序、并行分组、门禁判定、返工决策；</li>
 *   <li><b>{@link StepExecutor} 负责执行动作</b>——由 api 层实现并注入，
 *       因为组装运行时所需的 model 配置、toolkit、工作区路径都在 api 层，
 *       而 mode 模块只依赖 base（反向依赖会造成循环，构建失败）。</li>
 * </ul>
 * 现有三种实现：
 * <ul>
 *   <li>{@code SingleOrchestrator}（{@code "single"}）——单阶段单步，用默认执行流程；</li>
 *   <li>{@code TeamOrchestrator}（{@code "team"}）——按 workflow 编排多个智能体，
 *       覆盖 {@link #execute} 以实现验收与返工；</li>
 *   <li>{@code ScheduleOrchestrator}（{@code "schedule"}）——按计划触发。</li>
 * </ul>
 * <p>
 * <b>DB 兼容红线</b>：{@code modeId()} 的返回值会与 {@code ScenarioEntity.mode}
 * 字段的存量取值比对，而 SQLite 配置为 {@code ddl-auto: update} 不迁移历史值，
 * 因此 {@code "single"} / {@code "team"} 两个字面量<b>不可改名</b>。
 */
public interface AgentOrchestrator {

    /** 模式标识，须与 DB {@code ScenarioEntity.mode} 存量值一致 */
    String modeId();

    /** 模式展示名，用于前端与提示词渲染 */
    String displayName();

    /**
     * 依据执行上下文产出执行计划。
     *
     * @param ctx 执行上下文（含工作区、场景、任务）
     * @return 执行计划；调度失败（如 workflow 配置非法）时应返回带错误的计划而非抛异常
     */
    OrchestrationPlan plan(ExecutionContext ctx);

    /**
     * 执行计划。
     * <p>
     * <b>默认实现</b>委托给 {@link SequentialExecution}：stage 顺序执行、
     * stage 内并发派发、任一步失败即中止、步骤边界检查取消信号。
     * 这对 single（单阶段单步）语义完全正确，因而 {@code SingleOrchestrator} 无需覆盖。
     * <p>
     * <b>编排型模式应当覆盖本方法</b>，把自己的语义写在自己的模块里 ——
     * team 的「验收 + 返工」、schedule 的「条件触发」都属于各自模式的知识，
     * 不该在公共层堆 {@code if (modeId.equals(...))} 分支。
     * <p>
     * <b>返回 future 而非阻塞</b>：执行链末端是流式且可挂起的真实运行时
     * （见 {@link StepExecutor} 的说明），阻塞会同时废掉流式输出与工具确认。
     *
     * @param ctx      执行上下文
     * @param executor 步骤执行器，由 api 层注入
     * @return 执行结果 future；计划不可执行时返回已完成的 rejected 结果
     */
    default CompletableFuture<OrchestrationResult> execute(ExecutionContext ctx,
                                                           StepExecutor executor) {
        OrchestrationPlan plan = plan(ctx);
        if (!plan.executable()) {
            return CompletableFuture.completedFuture(OrchestrationResult.rejected(plan));
        }
        return SequentialExecution.run(modeId(), plan, ctx, executor);
    }

    /**
     * 行为规范提示词：本模式注入主控的行为约束与门禁要求（「协调者怎么做、
     * 验收怎么验、审计怎么报」）。
     * <p>
     * <b>默认 null</b>：本模式无行为规范追加（如 single —— 单智能体按基座协议执行）。
     * 编排型模式（team / schedule）覆盖本方法，返回 {@link OrchestrationBehavior}
     * 的渲染结果 —— 行为规范是模式自身的知识，不该由提示词渲染方硬编码。
     *
     * @param scenario          激活的场景
     * @param availableAgentIds 当前已注册、可作为执行体的智能体 id 集合
     * @return 渲染结果（含告警）；null 表示本模式无行为规范
     */
    default BehaviorSpec behaviorSpec(ScenarioProfile scenario, Set<String> availableAgentIds) {
        return null;
    }

    /**
     * 是否要求执行审计（门禁声明）。
     * <p>
     * 返回 true 时，系统侧在回合末比对「场景计划的阶段」与主控自报的
     * {@code <orchestration-audit>} 标记（见 {@code OrchestrationAuditVerifier}），
     * 把「编排是否真的按计划发生」由不可观测变为可观测。
     * <p>
     * 默认 false：single 模式无编排计划，无需审计。
     */
    default boolean requiresExecutionAudit() {
        return false;
    }

    /**
     * 本模式的主智能体是否使用「最小工具集」。
     * <p>
     * 返回 true 时，api 层装配主智能体 toolkit 不走通用六类工具 + HTTP/MCP 的
     * {@code createWorkspaceToolkit}，改用模式专属的最小工具集（如 ops 仅 remote_shell）。
     * 这是「场景决定能力边界」的声明点：模式自己声明 LLM 能触达什么，
     * api 层只负责按声明装配，不硬编码 {@code "ops".equals(mode)} 分支。
     * <p>
     * 默认 false：使用通用工具集（single / team / schedule）。
     */
    default boolean minimalToolkit() {
        return false;
    }

    /**
     * 本模式的主控智能体（SPI {@code EasyClawAgent} 的 agentId）。
     * <p>
     * 与 {@link #minimalToolkit()} 同构的「模式自声明」：主控人格由模式决定，
     * api 层（SystemPromptComposer）按本声明解析人格，不硬编码模式分支。
     * 场景 roleName 仍优先于本声明（场景绑定具体智能体时以绑定为准）。
     * <p>
     * 默认 {@code "main"}：single / team / schedule 沿用通用主控人格。
     */
    default String mainAgentId() {
        return "main";
    }

    /**
     * 本模式是否允许主控派遣子智能体。
     * <p>
     * 返回 false 时，主控的子 Agent 名册整体为空：不装配任何 SubagentDeclaration，
     * 系统提示词不含子 Agent 派遣说明，模型无从发起派遣。适用于「单执行体串行动作、
     * 无并行拆解需求」的模式（如 ops 的远程服务器操作）。
     * <p>
     * 默认 true：维持既有名册装配行为（single / team / schedule）。
     */
    default boolean subagentDispatchEnabled() {
        return true;
    }

    /**
     * 本模式是否启用「工具白名单（永久授权）」机制。
     * <p>
     * 返回 false 时：永久授权（allowPermanently）拒绝持久化、回合授权（allowTurn）
     * 不生效、存量授权规则也不摘除 system ASK 规则 —— 工具每次调用都弹用户确认。
     * 适用于「每次执行都必须人在环」的高危模式（如 ops 的 remote_shell）。
     * <p>
     * 默认 true：维持既有白名单行为（single / team / schedule）。
     */
    default boolean whitelistEnabled() {
        return true;
    }

    /**
     * 本模式是否要求场景配置工作流步骤（workflow）。
     * <p>
     * 判据是「执行计划是否可能多于一步、且步骤来自场景预配置的 workflow」：
     * team / schedule 按场景 workflow 编排，缺失步骤即配置不完整，必须拒绝保存；
     * 而 ops 这类<b>单执行体</b>模式（{@link #plan} 恒为单阶段单步、不消费 workflow）
     * 不应被此校验误伤 —— 用户编辑 ops 场景的任意字段都会撞上「需要至少一个工作流步骤」。
     * <p>
     * 默认「非 single 即要求」：与 {@link OrchestrationModes#isOrchestrated} 的历史
     * 行为等价（single / team / schedule 零变化）；单执行体模式覆写返回 false。
     */
    default boolean requiresWorkflowSteps() {
        return !OrchestrationModes.DEFAULT_MODE.equals(modeId());
    }
}