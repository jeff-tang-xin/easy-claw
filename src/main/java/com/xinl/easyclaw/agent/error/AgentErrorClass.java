package com.xinl.easyclaw.agent.error;

import java.util.List;
import java.util.Locale;

/**
 * 工具结果错误分类（抽离自 AgentService.inferToolResultState 的 19 个 startsWith 模式）。
 * <p>
 * 100% 覆盖原 19 个模式 + 保持"原 state 字符串透传"语义。
 * <p>
 * 行为兼容要点：
 * <ul>
 *   <li>大小写敏感规则必须保持：{@code [ERROR]} / {@code ❌} 是大写/Unicode 字面量，{@code tool not found} 等是 lower-case</li>
 *   <li>非 SUCCESS/RUNNING 的 state 原样透传（不强制改写为 "ERROR"）</li>
 *   <li>短文本 + 关键词启发式：&lt; 200 字符 + 不以代码字符开头 + 包含 failed/error/not found/failed to</li>
 * </ul>
 */
public enum AgentErrorClass {

    /** AgentScope 框架级错误（5 个大小写敏感前缀：[ERROR] / Error: / ❌ / Exception: / Caused by:） */
    FRAMEWORK_PREFIX(
            List.of("[ERROR]", "Error: ", "❌ ", "Exception: ", "Caused by: "),
            true,
            "框架级错误，请查看日志"),

    /** 工具或资源不存在（4 个：tool not found / unknown tool / file not found / string not found in file） */
    TOOL_NOT_FOUND(
            List.of("tool not found", "unknown tool", "file not found", "string not found in file"),
            false,
            "工具或资源不存在"),

    /** 权限不足（3 个：insufficient permissions / unauthorized tool call / permission denied） */
    PERMISSION_DENIED(
            List.of("insufficient permissions", "unauthorized tool call", "permission denied"),
            false,
            "权限不足"),

    /** 工具执行失败（4 个：tool execution failed / parameter validation failed / illegal argument / illegalargumentexception） */
    EXECUTION_FAILED(
            List.of("tool execution failed", "parameter validation failed", "illegal argument", "illegalargumentexception"),
            false,
            "工具执行失败"),

    /** 短文本 + 关键词启发式（4 个关键词：failed / error / not found / failed to） */
    SHORT_TEXT_WITH_ERROR_KEYWORD(
            List.of("failed", "error", "not found", "failed to"),
            false,
            "工具返回疑似错误（短文本）"),

    /** 非错误 */
    SUCCESS(List.of(), false, "成功");

    public final List<String> patterns;
    /** true → 在 trimmed &lt; 200 + 不以代码字符开头 的前提下做 contains 匹配 */
    public final boolean shortTextHeuristic;
    public final String userHint;

    AgentErrorClass(List<String> patterns, boolean shortTextHeuristic, String userHint) {
        this.patterns = patterns;
        this.shortTextHeuristic = shortTextHeuristic;
        this.userHint = userHint;
    }

    /**
     * 完整分类入口。
     *
     * @param state      AgentScope 框架传入的 state（可能 null）
     * @param resultText 工具结果文本（可能 null）
     * @return 错误分类；未匹配任何模式 → SUCCESS
     */
    public static AgentErrorClass classify(String state, String resultText) {
        // —— 优先用 framework state（快速路径）——
        if (state != null && !"SUCCESS".equalsIgnoreCase(state) && !"RUNNING".equalsIgnoreCase(state)) {
            return mapState(state);
        }
        if (resultText == null || resultText.isBlank()) {
            return state == null ? SUCCESS : mapState(state);
        }
        String trimmed = resultText.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // —— 19 个 prefix 模式（前 4 个 class 都是 prefix 模式，按 enum 顺序匹配）——
        for (AgentErrorClass c : List.of(FRAMEWORK_PREFIX, TOOL_NOT_FOUND, PERMISSION_DENIED, EXECUTION_FAILED)) {
            for (String p : c.patterns) {
                // 大小写敏感规则：纯大写/Unicode 字面量用 trimmed.startsWith，其他用 lower.startsWith
                if (p.equals(p.toUpperCase(Locale.ROOT))) {
                    if (trimmed.startsWith(p)) {
                        return c;
                    }
                } else {
                    if (lower.startsWith(p.toLowerCase(Locale.ROOT))) {
                        return c;
                    }
                }
            }
        }

        // —— 短文本 + 关键词启发式 ——
        if (isShortTextAndLooksLikeError(trimmed, lower)) {
            return SHORT_TEXT_WITH_ERROR_KEYWORD;
        }
        return state == null ? SUCCESS : mapState(state);
    }

    /**
     * 便捷方法：与原 AgentService.inferToolResultState 返回 String 保持 100% 兼容。
     * <ul>
     *   <li>SUCCESS + 原 state 非 null → 返回原 state（如 "PARTIAL" / "FAILED" 等透传）</li>
     *   <li>SUCCESS + 原 state null → 返回 "SUCCESS"</li>
     *   <li>其他 enum → 返回 enum.name()（"ERROR"）</li>
     * </ul>
     */
    public static String classifyToString(String state, String resultText) {
        AgentErrorClass cls = classify(state, resultText);
        if (cls == SUCCESS) {
            return state != null ? state : "SUCCESS";
        }
        return cls.name();
    }

    private static boolean isShortTextAndLooksLikeError(String trimmed, String lower) {
        if (trimmed.length() >= 200) {
            return false;
        }
        // 6 个"代码字符开头"白名单 + package / import 关键字 + 换行符
        if (trimmed.startsWith("\"") || trimmed.startsWith("{")
                || trimmed.startsWith("[") || trimmed.startsWith("`")
                || trimmed.startsWith("<") || trimmed.startsWith("package ")
                || trimmed.startsWith("import ") || trimmed.contains("\n")) {
            return false;
        }
        // 4 个关键词任一 → ERROR
        return lower.contains("failed") || lower.contains("error")
                || lower.contains("not found") || lower.contains("failed to");
    }

    private static AgentErrorClass mapState(String state) {
        if (state == null) {
            return SUCCESS;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "ERROR", "FAILED", "FAIL" -> FRAMEWORK_PREFIX;
            default -> SUCCESS;  // 未知 state → 透传（在 classifyToString 中由调用方处理）
        };
    }
}
