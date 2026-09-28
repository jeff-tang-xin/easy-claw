package com.xinl.easyclaw.base.agent;

import java.util.List;

/**
 * 子智能体派遣策略。
 * <p>
 * <b>派遣是所有 7 个智能体的通用能力</b>，不是某种模式的特权。无论 single 还是
 * team，无论自己是否正被编排，每个智能体都可以派遣子智能体来完成子任务。
 * <p>
 * <b>派遣语义（关键，勿改）</b>：
 * <ul>
 *   <li><b>派遣不嵌套</b>——被派遣的子智能体作为<b>一次性任务</b>执行，
 *       不再拥有继续派遣的能力（框架层 {@code NO spawning further subagents}
 *       已硬性禁止二级派发）。因此整个体系恒为「编排一层 + 派遣一层」。</li>
 *   <li><b>被派遣者不进 workflow</b>——team 流程中某一步的智能体派出的子智能体，
 *       只向派遣者交付结果，不作为 workflow 的一个 stage。</li>
 * </ul>
 * <b>超时分档</b>由 {@code timeoutSeconds} 表达：派发必须显式指定超时，
 * 不传会落到框架默认 30s 而被掐断（force-sync 下不降级为后台任务，
 * 已完成的工作除黑板外全部蒸发）。上限 600s 为框架硬限制。
 *
 * @param allowedTargets 可派遣的智能体 id；空列表表示可派遣全部
 * @param maxConcurrency 同阶段最大并发派遣数
 * @param timeoutSeconds 默认派遣超时（秒），上限 600
 */
public record DispatchPolicy(List<String> allowedTargets,
                             int maxConcurrency,
                             int timeoutSeconds) {

    /** 框架硬上限：传更大的值会被静默截断为 600 */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /** 禁止派遣子智能体 */
    public static final DispatchPolicy DISABLED = new DispatchPolicy(List.of(), 0, 0);

    public DispatchPolicy {
        allowedTargets = allowedTargets == null ? List.of() : List.copyOf(allowedTargets);
        if (timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            timeoutSeconds = MAX_TIMEOUT_SECONDS;
        }
    }

    /** 是否允许派遣 */
    public boolean enabled() {
        return maxConcurrency > 0;
    }

    /** 是否可派遣指定目标 */
    public boolean canDispatch(String agentId) {
        return enabled() && (allowedTargets.isEmpty() || allowedTargets.contains(agentId));
    }
}