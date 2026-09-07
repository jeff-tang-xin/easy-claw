package com.xinl.easyclaw.tool.service;

import java.util.List;
import java.util.Set;

/**
 * 工具确认策略的**唯一权威来源**。
 * <p>
 * 背景：AgentScope 的 {@code PermissionEngine.continueAfterToolCheck} 判定顺序为
 * deny → ask → allow → BYPASS，**没有任何规则命中时落到默认 ASK**
 * （见 {@code PermissionEngine.java:196} 的 "6. Default (ASK, or DENY under DONT_ASK)"）。
 * 因此工具只有两种归属：
 * <ul>
 *   <li>在 {@link #SILENTLY_ALLOWED} 里 —— 注册 system ALLOW 规则，静默执行，用户完全无感；
 *       把它放进「工具白名单」页面纯属噪声，用户点开关也不会改变任何行为</li>
 *   <li>不在里面 —— 无规则或挂 system ASK 规则，调用时都会弹确认，
 *       因此**需要**出现在白名单页面，供用户永久授权</li>
 * </ul>
 * 之所以单独抽出这个类：此前该清单在 {@code WorkspaceAgentBuilder}（构建权限上下文用）
 * 与前端授权页面（展示用）各写一份，两边必然漂移 ——
 * 一旦漂移，用户看到的开关与引擎实际行为就不一致（开关点了没用，或该给的授权入口消失）。
 * 现在权限上下文与前端列表都从这里取，物理上无法再分叉。
 *
 * <p><b>修改须知</b>：往 {@link #SILENTLY_ALLOWED} 加名字等于给该工具永久免确认，
 * 只能加**无副作用的纯读取/纯计算**工具。凡是落盘、联网写、执行外部进程、
 * 派生子 Agent 的工具都必须留在确认侧。
 */
public final class ToolPermissionPolicy {

    private ToolPermissionPolicy() {
    }

    /**
     * 静默放行的只读工具：不改磁盘、不执行外部进程、不派活给子 Agent。
     * <p>
     * 说明几个容易看错的条目：
     * <ul>
     *   <li>{@code format_code} 在此 —— 它只对传入的**代码文本**做空行/行尾空白清理并把结果
     *       返回给 LLM，不写文件（写回要靠 edit_file，那一步会另行确认）</li>
     *   <li>{@code analyze_code} / {@code diff_code} / {@code inspect_data} 同理，纯文本分析</li>
     *   <li>{@code memory_search} / {@code memory_get} 在此，但 {@code memory_save} <b>不在</b> ——
     *       后者会真正改写 MEMORY.md</li>
     *   <li>{@code web_search} / {@code fetch_webpage} 视为只读：仅取回内容，不对外提交数据</li>
     * </ul>
     * 注意本清单**刻意不包含** {@code session_*} 与 {@code agent_*}/{@code task_*} 等
     * 编排类工具 —— 它们当前走默认 ASK。这是既有行为，本次未改动；
     * 若要放行需单独评估（详见交付说明）。
     */
    private static final Set<String> SILENTLY_ALLOWED = Set.of(
            "read_file", "list_files", "list_directory", "glob_files", "grep_files",
            "search_files", "analyze_code", "format_code", "diff_code",
            "web_search", "fetch_webpage",
            "memory_search", "memory_get",
            // 共享黑板：只读写「本次任务的协作记录本」，不触碰用户文件、不对外发送。
            // 必须放行的原因：黑板是并行子 Agent 之间唯一的信息通道，若走默认 ASK，
            // 每登记一条结论都要弹一次确认，而子 Agent 的确认请求对用户来说毫无上下文，
            // 实际效果等于把多智能体协作变成不断打断用户 —— 权限收益为零，代价是功能不可用。
            // blackboard_append 虽是写操作，写入范围仅限本次会话的黑板存储，故与只读同档。
            "blackboard_read", "blackboard_append"
    );

    /**
     * 需要用户确认、且**必须显式注册 system ASK 规则**的写/执行类工具。
     * <p>
     * 为什么只有这三个而不是「所有非只读工具」：无规则本就默认 ASK，
     * 显式挂 ASK 规则是为了让 {@code PermissionContextState} 里能看到明确声明
     * （便于排查「为什么这个工具弹窗了」）。这里保持与历史行为完全一致，不扩大。
     */
    private static final List<String> EXPLICIT_ASK = List.of(
            "write_file", "edit_file", "execute"
    );

    /** 静默放行的只读工具名（不可变） */
    public static Set<String> silentlyAllowed() {
        return SILENTLY_ALLOWED;
    }

    /** 需显式注册 system ASK 规则的工具名（保持声明顺序） */
    public static List<String> explicitAsk() {
        return EXPLICIT_ASK;
    }

    /**
     * 该工具调用时是否会向用户征求确认。
     * <p>
     * 等价于「不在静默放行清单里」—— 因为引擎默认行为就是 ASK。
     * 未知工具名（如 MCP 动态工具）一律返回 {@code true}：
     * fail-closed，宁可多问一次，不可默认放行。
     *
     * @param toolName 工具名；{@code null}/空白按需要确认处理
     */
    public static boolean requiresConfirm(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return true;
        }
        return !SILENTLY_ALLOWED.contains(toolName);
    }
}
