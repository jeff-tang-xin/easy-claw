package com.xinl.easyclaw.memory.flush;

/**
 * 提取载荷切片器（纯函数，无状态）：按游标框定「本轮提取的消息区间」。
 *
 * <p>语义：不丢消息、窗口有界、游标单调推进——
 * <ul>
 *   <li>增量不超过窗口：全取，游标推进到上下文末尾；</li>
 *   <li>增量超过窗口：只取<strong>最早</strong>一批（窗口大小），游标推进到该批末尾，
 *       剩余留待下轮（下轮从本批末尾继续）——延迟但不丢；</li>
 *   <li>游标越过上下文末尾（发生过压缩，旧下标失效）：重置为「最后 window 条」起点，
 *       宁可重复提取（提取模型按 MEMORY.md/daily 去重）不可遗漏。</li>
 * </ul>
 */
public final class FlushSlicePlanner {

    private FlushSlicePlanner() {
    }

    /**
     * 切片结果。
     *
     * @param from           本批新消息起始下标（含）
     * @param to             本批新消息结束下标（不含）；即成功后的新游标
     * @param backgroundFrom 背景消息起始下标（[backgroundFrom, from) 为窗口前背景，供提取模型理解语境）
     */
    public record Slice(int from, int to, int backgroundFrom) {

        public boolean isEmpty() {
            return to <= from;
        }

        public int newCount() {
            return to - from;
        }
    }

    /**
     * @param contextSize 当前上下文消息总数
     * @param cursor      已提取到的游标（下标，不含）
     * @param window      单次提取窗口上限（条，≥1）
     * @param background  窗口前携带的背景条数（≥0）
     */
    public static Slice plan(int contextSize, int cursor, int window, int background) {
        if (contextSize <= 0) {
            return new Slice(0, 0, 0);
        }
        int safeCursor = Math.max(0, cursor);
        if (safeCursor > contextSize) {
            // 压缩后旧游标失效：重置为最后 window 条起点（重复可去重，遗漏不可追）
            safeCursor = Math.max(0, contextSize - window);
        }
        int to = Math.min(contextSize, safeCursor + window);
        int backgroundFrom = Math.max(0, safeCursor - background);
        return new Slice(safeCursor, to, backgroundFrom);
    }
}
