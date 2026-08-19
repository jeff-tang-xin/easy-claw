package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.EarlyTermination;
import com.embabel.agent.core.EarlyTerminationPolicy;

import java.time.Duration;

/**
 * 墙钟时间预算：运行超过 maxDuration 后自动终止。
 * <p>
 * 补充 Embabel 内置的 Budget（actions/cost/tokens）——这些只能限制 GOAP 规划层的动作数，
 * 但无法阻止 LLM 陷入死循环或子 Agent 无限探索。墙钟时间是兜底保障。
 */
public class TimeBudgetPolicy implements EarlyTerminationPolicy {

    private final long maxMillis;

    public TimeBudgetPolicy(Duration maxDuration) {
        this.maxMillis = maxDuration.toMillis();
    }

    @Override
    public EarlyTermination shouldTerminate(AgentProcess process) {
        long elapsed = process.getRunningTime().toMillis();
        if (elapsed > maxMillis) {
            return new EarlyTermination(process, true,
                    "运行超时（" + Duration.ofMillis(elapsed).toSeconds() + "s > 上限 "
                            + Duration.ofMillis(maxMillis).toSeconds() + "s）", this);
        }
        return null;
    }

    @Override
    public String getName() {
        return "time-budget-" + Duration.ofMillis(maxMillis).toSeconds() + "s";
    }
}
