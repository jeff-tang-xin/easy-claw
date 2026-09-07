package com.xinl.easyclaw.base.agent;

/**
 * 提示词贡献——SPI 的<b>输出</b>之一。
 * <p>
 * 智能体只贡献「你是谁」的人格与职责片段，<b>不重抄基座层</b>
 * （工具协议、安全规范、分层约定由 agent-core 统一注入）。
 * 这样既避免每个智能体重复一遍同样的话，也保证安全规范无法被角色层覆盖。
 *
 * @param persona   人格与职责片段，可为 null 表示无覆盖
 * @param appendix  追加段（如该智能体特有的方法论提示），可为 null
 */
public record PromptContribution(String persona, String appendix) {

    /** 无贡献：调用方原样使用基础提示词 */
    public static final PromptContribution NONE = new PromptContribution(null, null);

    public static PromptContribution ofPersona(String persona) {
        return new PromptContribution(persona, null);
    }

    /** 两段是否都为空 */
    public boolean isEmpty() {
        return isBlank(persona) && isBlank(appendix);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}