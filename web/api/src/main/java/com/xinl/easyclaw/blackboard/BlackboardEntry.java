package com.xinl.easyclaw.blackboard;

/**
 * 记录本的一条登记（append-only，落盘为 JSONL 单行）。
 * <p>
 * 记录本按「工作区（local）/ 项目+用户（cloud）」隔离，<b>不按会话分本</b>——
 * 它是个人跨会话的心得账本：新会话能读到旧会话留下的结论。
 *
 * @param seq       记录本内自增序号，从 1 开始；<b>排序以此为准</b>
 * @param ts        登记时刻（ISO-8601），仅用于展示，不参与排序
 * @param author    登记者（主 Agent 为 {@code main}，子 Agent 为 {@code sub-<尾段>}）
 * @param type      条目类型：note / finding / risk / conclusion
 * @param content   正文（超长已在写入前截断）
 * @param sessionId 登记来源会话 id（标注「哪次会话写的」；历史条目/平台条目可能为 null）
 */
public record BlackboardEntry(long seq, String ts, String author, String type, String content, String sessionId) {
}
