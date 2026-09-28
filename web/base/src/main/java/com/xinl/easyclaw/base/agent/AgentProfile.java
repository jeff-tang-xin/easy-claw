package com.xinl.easyclaw.base.agent;

/**
 * 智能体展示信息。
 *
 * @param displayName 显示名，如「AI-CLAW 通用智能体」
 * @param description 一句话职责描述，用于前端选择列表与编排提示词
 * @param icon        图标标识，可为 null
 */
public record AgentProfile(String displayName, String description, String icon) {

    public AgentProfile {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AgentProfile.displayName 不能为空");
        }
    }

    public static AgentProfile of(String displayName, String description) {
        return new AgentProfile(displayName, description, null);
    }
}