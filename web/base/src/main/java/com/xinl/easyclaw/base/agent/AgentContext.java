package com.xinl.easyclaw.base.agent;

import com.xinl.easyclaw.base.profile.ScenarioProfile;

/**
 * 智能体构建上下文——SPI 的<b>输入</b>。
 * <p>
 * 传给 {@link EasyClawAgent#prompt(AgentContext)} 的只读上下文，让智能体能
 * 依据当前工作区、场景来定制自己的提示词片段。
 * <p>
 * 有意设计为只读值对象：智能体实现不应反向修改运行时状态，
 * 所有定制都通过返回值（{@link PromptContribution} 等）表达。
 *
 * @param workspaceId 当前工作区标识
 * @param scenario    激活场景，可为 null（无场景绑定）
 * @param teamMode    当前是否处于 team 编排模式下执行
 */
public record AgentContext(String workspaceId,
                           ScenarioProfile scenario,
                           boolean teamMode) {

    /** 场景是否已配置 */
    public boolean hasScenario() {
        return scenario != null;
    }
}