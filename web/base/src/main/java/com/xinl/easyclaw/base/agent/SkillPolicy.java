package com.xinl.easyclaw.base.agent;

import java.util.List;

/**
 * 技能维行为边界：人格基本准则的强制部分（原「技能授权策略」）。
 * <p>
 * 与 {@link ToolPolicy} 同属「基本准则的强制部分」。
 * <p>
 * 取代原先 {@code SubagentLoader.restrictSkills} 的统一裁剪逻辑。
 *
 * @param allowed 允许加载的 skill id；空列表表示不限制
 */
public record SkillPolicy(List<String> allowed) {

    public static final SkillPolicy UNRESTRICTED = new SkillPolicy(List.of());

    public SkillPolicy {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    public static SkillPolicy of(String... skills) {
        return new SkillPolicy(List.of(skills));
    }

    public boolean unrestricted() {
        return allowed.isEmpty();
    }
}