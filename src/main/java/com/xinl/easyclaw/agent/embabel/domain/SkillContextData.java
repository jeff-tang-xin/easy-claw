package com.xinl.easyclaw.agent.embabel.domain;

public record SkillContextData(
        String skillName,
        String skillContent
) {
    public boolean isEmpty() {
        return skillContent == null || skillContent.isBlank();
    }
}
