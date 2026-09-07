package com.xinl.easyclaw.base.orchestration;

import com.xinl.easyclaw.base.profile.ScenarioProfile;

/**
 * 智能体调度器 SPI。
 * <p>
 * <b>调度器不是智能体</b>——它只决定「这次任务派哪些智能体、以什么顺序跑」，
 * 执行单元始终是实现了 {@code EasyClawAgent} 的那 7 个平级智能体。
 * <p>
 * 现有两种实现：
 * <ul>
 *   <li>{@code SingleOrchestrator}（modeId = {@code "single"}）——
 *       用户在场景中自选一个智能体，未选时默认 AI-CLAW 通用智能体，计划为单步。</li>
 *   <li>{@code TeamOrchestrator}（modeId = {@code "team"}）——
 *       按场景配置的 workflow 编排多个智能体，支持顺序与并行。</li>
 * </ul>
 * team 本质是 single 的编排包装：执行单元同一套，team 只多一层流程编排。
 * <p>
 * <b>DB 兼容红线</b>：{@code modeId()} 的返回值会与 {@code ScenarioEntity.mode}
 * 字段的存量取值比对，而 SQLite 配置为 {@code ddl-auto: update} 不迁移历史值，
 * 因此 {@code "single"} / {@code "team"} 两个字面量<b>不可改名</b>。
 */
public interface AgentOrchestrator {

    /** 模式标识，取值 {@code "single"} 或 {@code "team"}，须与 DB 存量值一致 */
    String modeId();

    /** 模式展示名，用于前端与提示词渲染 */
    String displayName();

    /**
     * 依据场景配置与本次任务，产出执行计划。
     *
     * @param scenario 当前激活场景（可为 null，表示无场景绑定）
     * @param task     用户本次输入的任务
     * @return 执行计划；调度失败（如 workflow 配置非法）时应返回带错误的计划而非抛异常
     */
    OrchestrationPlan plan(ScenarioProfile scenario, String task);
}