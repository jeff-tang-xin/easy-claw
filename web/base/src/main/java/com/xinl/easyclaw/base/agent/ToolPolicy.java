package com.xinl.easyclaw.base.agent;

import java.util.List;
import java.util.Map;

/**
 * 工具授权策略。
 * <p>
 * 取代原先硬编码在 {@code SubagentLoader} 的三处全局常量
 * （{@code KNOWN_TOOL_NAMES} / {@code TOOL_NAME_ALIASES} / {@code restrictTools}），
 * 让每个智能体自行声明「我能用哪些工具」。
 * <p>
 * <b>别名映射的既有陷阱</b>（务必保留此行为）：harness 的
 * {@code allowlistedInheritedToolkit} 按名<b>严格相等</b>裁剪工具，对不上就静默移除。
 * 因此 {@code shell} → {@code execute}、{@code grep} → {@code grep_files} 之类的
 * 归一化必须在授权前完成，否则工具会静默失能。
 * 注意别名只对声明生效，<b>对提示词正文无效</b>——正文里必须写工具真名。
 *
 * @param allowed 允许使用的工具名（已是真名）；空列表表示不限制
 * @param aliases 别名 → 真名的映射
 */
public record ToolPolicy(List<String> allowed, Map<String, String> aliases) {

    /** 不做限制：继承调用方的全部工具 */
    public static final ToolPolicy UNRESTRICTED = new ToolPolicy(List.of(), Map.of());

    public ToolPolicy {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    }

    public static ToolPolicy of(String... tools) {
        return new ToolPolicy(List.of(tools), Map.of());
    }

    /** 是否不限制工具 */
    public boolean unrestricted() {
        return allowed.isEmpty();
    }

    /** 归一化单个工具名：命中别名则返回真名，否则原样返回 */
    public String normalize(String toolName) {
        if (toolName == null) {
            return null;
        }
        return aliases.getOrDefault(toolName, toolName);
    }
}