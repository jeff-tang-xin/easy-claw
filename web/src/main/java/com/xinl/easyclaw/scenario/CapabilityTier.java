package com.xinl.easyclaw.scenario;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子智能体/主智能体的「基础能力档位」。
 * <p>
 * <b>为什么需要档位</b>：harness 的 {@code allowlistedInheritedToolkit} 是一刀切裁剪 ——
 * 凡不在白名单内的工具全部移除，且不区分工具来源。因此若只把 MCP 工具名写进白名单，
 * 子智能体会连 {@code read_file} / {@code execute} 都失去，直接变成废子。
 * <p>
 * 档位的作用就是补齐这批「其余工具」：最终白名单 = 档位工具 ∪ 绑定的 MCP 工具。
 * <p>
 * 工具名与 {@code ToolRegistryService} 的框架分组常量保持一致。
 */
public enum CapabilityTier {

    /** 不给任何基础工具：仅保留显式绑定的 MCP 工具（极特殊用途） */
    NONE,

    /** 只读：读文件 + 检索 + 记忆/会话查询。适合 reviewer、researcher */
    READONLY,

    /** 标准（默认）：只读 + 写文件 + skill 脚本执行。适合 coder、file-expert */
    STANDARD,

    /** 完整：标准 + Shell + 子 Agent 调度。适合需要跑构建或再派活的编排者 */
    FULL;

    private static final List<String> READ_TOOLS = List.of(
            "read_file", "grep_files", "glob_files", "list_files",
            "memory_search", "memory_get",
            "session_search", "session_list", "session_history");

    private static final List<String> WRITE_TOOLS = List.of("write_file", "edit_file");

    /**
     * 自定义 @Tool（不属于框架分组）。归入 STANDARD 的理由：
     * 若绑定了 skill 却不给 run_skill_script，skill 里的脚本就跑不起来，
     * 这种「配了却不能用」的组合最容易让人困惑。
     */
    private static final List<String> SKILL_TOOLS = List.of("run_skill_script", "run_python");

    private static final List<String> SHELL_TOOLS = List.of("execute");

    private static final List<String> SUBAGENT_TOOLS = List.of(
            "agent_spawn", "agent_send", "agent_list",
            "task_output", "task_cancel", "task_list");

    /** 该档位包含的工具名集合（保持声明顺序，便于日志与前端展示） */
    public Set<String> toolNames() {
        Set<String> names = new LinkedHashSet<>();
        if (this == NONE) {
            return names;
        }
        names.addAll(READ_TOOLS);
        if (this == READONLY) {
            return names;
        }
        names.addAll(WRITE_TOOLS);
        names.addAll(SKILL_TOOLS);
        if (this == STANDARD) {
            return names;
        }
        names.addAll(SHELL_TOOLS);
        names.addAll(SUBAGENT_TOOLS);
        return names;
    }

    /**
     * 宽松解析档位名（大小写不敏感），无法识别时回退默认档位。
     * <p>用户配置错字不应导致工作区加载失败 —— 与 {@code ScenarioResolver} 的降级哲学一致。
     */
    public static CapabilityTier parse(String raw, CapabilityTier fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        for (CapabilityTier tier : values()) {
            if (tier.name().equalsIgnoreCase(raw.trim())) {
                return tier;
            }
        }
        return fallback;
    }
}
