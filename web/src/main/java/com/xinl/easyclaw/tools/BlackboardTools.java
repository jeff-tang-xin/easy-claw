package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.blackboard.BlackboardEntry;
import com.xinl.easyclaw.blackboard.BlackboardKeys;
import com.xinl.easyclaw.blackboard.BlackboardStore;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 共享记录本工具（blackboard）：给互相看不见的并行子 Agent 一块公共黑板。
 * <p>
 * team 模式下每个子 Agent 是独立会话、独立上下文，彼此的发现无法互通，主 Agent 也只能在
 * 子任务结束时拿到一段总结。本工具让任何角色随时把「结论/建议/风险」登记进同一个记录本，
 * 别人开工前读一遍就能站在同伴的结论上继续，而不是各干各的最后再拼。
 * <p>
 * 隔离键取 {@code ec.blackboardKey}（主会话写入、子 Agent 继承），因此同一次任务的
 * 主 Agent 与全部子 Agent 共享一块黑板，不同会话之间互不干扰。
 * <p>
 * 只提供追加与读取，<b>刻意不提供删除/清空</b>：黑板的价值在于不可抵赖的累积记录。
 * {@code author} 也不是入参，而是从运行时上下文解析 —— 不给冒名登记的机会。
 * WorkspaceContext / RuntimeContext 由框架注入，不暴露给 LLM。
 */
@Component
public class BlackboardTools {

    private static final Logger log = LoggerFactory.getLogger(BlackboardTools.class);

    /** 条目类型白名单；LLM 传别的值一律归为 note，而不是报错打断它 */
    private static final Set<String> TYPES = Set.of("note", "finding", "risk", "conclusion");

    /** 单次读取渲染文本上限（字符）：超长时丢最早的，保最新的 */
    private static final int MAX_RENDER_CHARS = 12_000;

    private final BlackboardStore store;

    public BlackboardTools(BlackboardStore store) {
        this.store = store;
    }

    @Tool(name = "blackboard_append", description = "把一条【结论/发现/建议/风险】登记到本次任务的共享记录本，供主 Agent 和其他并行子 Agent 读到。\n"
            + "【何时用】得出可复用的结论、发现影响他人做法的风险、确定了某个方案或数据口径时——立即登记，不要等到最后汇总。\n"
            + "【不要用于】记录过程日志、中间草稿、心情或计划（这些写在自己的回复里即可）；也不要用它给用户输出最终答案。\n"
            + "【参数】type：note（补充说明）/ finding（发现的事实）/ risk（风险与坑）/ conclusion（结论与决定），传其他值按 note 处理；content：一句话说清「是什么 + 对别人意味着什么」，不要贴大段原文。")
    public String blackboardAppend(
            @ToolParam(name = "type", description = "条目类型：note / finding / risk / conclusion") String type,
            @ToolParam(name = "content", description = "登记内容，一句话说清结论及其影响，不要贴大段原文") String content,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        if (content == null || content.isBlank()) {
            return "❌ content 不能为空：请写清要登记的结论或风险。";
        }
        if (workspace == null) {
            return "❌ 当前没有可用的工作区，无法登记。";
        }
        try {
            String safeType = type != null && TYPES.contains(type.trim().toLowerCase()) ? type.trim().toLowerCase() : "note";
            return store.append(workspace, resolveKey(rc), resolveAuthor(rc), safeType, content.trim());
        } catch (Exception e) {
            log.error("登记记录本失败", e);
            return "❌ 登记失败: " + e.getMessage();
        }
    }

    @Tool(name = "blackboard_read", description = "读取本次任务共享记录本里最近的条目，看看主 Agent 和其他子 Agent 已经得出了什么结论、发现了什么风险。\n"
            + "【何时用】接到子任务开工前、给子 Agent 派活前、准备汇总或收尾前各读一次，避免重复劳动或与同伴结论冲突。\n"
            + "【不要用于】当成通用文件读取或日志检索（读文件用 read_file）；也不要在同一轮里反复调用，内容只在有人登记后才变化。\n"
            + "【参数】limit：返回最近多少条，留空默认 30，最大 100。")
    public String blackboardRead(
            @ToolParam(name = "limit", description = "返回最近多少条，留空默认 30，最大 100", required = false) Integer limit,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        if (workspace == null) {
            return "❌ 当前没有可用的工作区，无法读取记录本。";
        }
        try {
            List<BlackboardEntry> entries = store.read(workspace, resolveKey(rc),
                    limit == null ? BlackboardStore.DEFAULT_READ_LIMIT : limit);
            if (entries.isEmpty()) {
                return "（记录本为空：还没有人登记过结论。有结论后请用 blackboard_append 登记。）";
            }
            return render(entries);
        } catch (Exception e) {
            log.error("读取记录本失败", e);
            return "❌ 读取失败: " + e.getMessage();
        }
    }

    /**
     * 渲染成 LLM 易读的行文本；总长超限时<b>丢最早的、保最新的</b>
     * —— 上下文有限时，最新结论比历史条目更有决策价值。
     */
    private String render(List<BlackboardEntry> entries) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        // 先从后往前累计长度，定位能容纳的起始位置
        int used = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            used += lineOf(entries.get(i)).length();
            if (used > MAX_RENDER_CHARS) {
                start = i + 1;
                break;
            }
        }
        if (start > 0) {
            sb.append("（内容较多，已省略较早的 ").append(start).append(" 条）\n");
        }
        // 保底：即使最新一条自身就超长，也必须吐出它 —— 否则调用方只看到「已省略 N 条」
        // 却读不到任何内容，比截断更糟（会误以为黑板是空的）。
        if (start >= entries.size()) {
            start = entries.size() - 1;
        }
        for (int i = start; i < entries.size(); i++) {
            sb.append(lineOf(entries.get(i)));
        }
        return sb.toString();
    }

    private String lineOf(BlackboardEntry e) {
        return "#" + e.seq() + " [" + e.type() + "] " + e.author() + " @ " + e.ts() + "\n"
                + e.content() + "\n\n";
    }

    /** 记录本隔离键：优先父会话继承来的 key，取不到退回自身 sessionId（至少不串台） */
    private String resolveKey(RuntimeContext rc) {
        if (rc == null) {
            return "default";
        }
        Object key = rc.get(BlackboardKeys.CTX_KEY);
        if (key instanceof String s && !s.isBlank()) {
            return s;
        }
        String sid = rc.getSessionId();
        return sid == null || sid.isBlank() ? "default" : sid;
    }

    /**
     * 登记者名：主 Agent 的 sessionId 等于 blackboardKey → {@code main}；
     * 子 Agent（sessionId 形如 {@code sub-<UUID>}）取尾 6 位，够区分且不冗长。
     */
    private String resolveAuthor(RuntimeContext rc) {
        if (rc == null) {
            return "unknown";
        }
        String sid = rc.getSessionId();
        if (sid == null || sid.isBlank()) {
            return "unknown";
        }
        Object key = rc.get(BlackboardKeys.CTX_KEY);
        if (key instanceof String s && s.equals(sid)) {
            return "main";
        }
        return "sub-" + (sid.length() <= 6 ? sid : sid.substring(sid.length() - 6));
    }
}
