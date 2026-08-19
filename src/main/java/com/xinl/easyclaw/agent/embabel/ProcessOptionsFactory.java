package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.core.Budget;
import com.embabel.agent.core.EarlyTerminationPolicy;
import com.embabel.agent.core.ProcessOptions;

import java.time.Duration;

/**
 * Embabel ProcessOptions 工厂。集中管理所有时间/动作/cost 限制，防止子 Agent 无限运行。
 * <p>
 * 限制分层：
 * <ul>
 *   <li>GOAP Budget: 动作数上限，防止规划层死循环</li>
 *   <li>墙钟时间: 兜底，防止 LLM 内层死循环（GOAP Budget 不覆盖 context.ai() 里的 while loop）</li>
 *   <li>ProcessControl 内置的 ON_STUCK: 框架自动检测卡死的 Action</li>
 * </ul>
 */
public final class ProcessOptionsFactory {

    private ProcessOptionsFactory() {}

    /** 根进程（OrchestratorAgent）最大时长：5 分钟 */
    public static final Duration ROOT_MAX_TIME = Duration.ofMinutes(5);
    /** 根进程最大 GOAP 动作数 */
    public static final int ROOT_MAX_ACTIONS = 25;
    /** 根进程最大 cost（USD） */
    public static final double ROOT_MAX_COST = 10.0;

    /** 子进程（Subagent）最大时长：60 秒 — 子 Agent 只做一件事，不应久 */
    public static final Duration CHILD_MAX_TIME = Duration.ofSeconds(60);
    /** 子进程最大 GOAP 动作数：子 Agent 单一职责，不超过 3 步 */
    public static final int CHILD_MAX_ACTIONS = 3;
    /** 子进程最大 token 数（单次 LLM 调用上限） */
    public static final int CHILD_MAX_TOKENS = 8000;
    /** 子进程最大 cost（USD） */
    public static final double CHILD_MAX_COST = 1.0;

    /**
     * 带事件监听的根进程 ProcessOptions（AgentService 主入口用）。
     */
    public static ProcessOptions forRoot(com.embabel.agent.api.event.AgenticEventListener listener) {
        TimeBudgetPolicy timeBudget = new TimeBudgetPolicy(ROOT_MAX_TIME);
        Budget budget = Budget.DEFAULT
                .withActions(ROOT_MAX_ACTIONS)
                .withCost(ROOT_MAX_COST);
        ProcessOptions opts = ProcessOptions.DEFAULT
                .withBudget(budget)
                .withAdditionalEarlyTerminationPolicy(timeBudget);
        if (listener != null) {
            opts = opts.withListener(listener);
        }
        return opts;
    }

    /**
     * 不带监听的根进程 ProcessOptions（REST /api/embabel/chat 用）。
     */
    public static ProcessOptions forRoot() {
        return forRoot(null);
    }

    /**
     * 子进程 ProcessOptions：独立超时 + 较小 Budget。
     * <p>
     * 子 Agent 超时后触发 STUCK 事件，由 AgentService 降级到 OrchestratorAgent 兜底。
     *
     * @param listener 事件监听器（通常复用根进程的 EventBridge）
     */
    public static ProcessOptions forChild(com.embabel.agent.api.event.AgenticEventListener listener) {
        TimeBudgetPolicy timeBudget = new TimeBudgetPolicy(CHILD_MAX_TIME);
        Budget budget = Budget.DEFAULT
                .withActions(CHILD_MAX_ACTIONS)
                .withTokens(CHILD_MAX_TOKENS)
                .withCost(CHILD_MAX_COST);
        ProcessOptions opts = ProcessOptions.DEFAULT
                .withBudget(budget)
                .withAdditionalEarlyTerminationPolicy(timeBudget)
                .withEphemeral(true)
                .withPrune(true);
        if (listener != null) {
            opts = opts.withListener(listener);
        }
        return opts;
    }

    /**
     * 不带监听的子进程 ProcessOptions。
     */
    public static ProcessOptions forChild() {
        return forChild(null);
    }
}
