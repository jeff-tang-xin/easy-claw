package com.xinl.easyclaw.blackboard;

import com.xinl.easyclaw.workspace.WorkspaceContext;

import java.util.List;

/**
 * 共享记录本（blackboard）存储接口：append-only，谁都能读。
 * <p>
 * 存在的意义：team 模式下并行子 Agent 互不可见（各自独立会话、独立上下文），
 * 谁也读不到同伴的结论。本存储给它们一块公共黑板 —— 只能追加、谁都能读，
 * 从而让「A 发现的风险」能影响「B 的做法」。
 * <p>
 * 双模存储：本地模式由 {@link LocalBlackboardStore} 落工作区 JSONL 文件；
 * cloud 模式（配置了 app-key）由 {@link CloudBlackboardStore} 委托 hub 的
 * {@code /api/spoke/blackboard/*} 端点。注入方（AI 工具与 REST controller）统一经
 * {@link RoutingBlackboardStore} 分流，对接口无感。
 * <p>
 * 两条不变式跨实现成立：<b>只追加、不删改</b>（不可抵赖的协作轨迹）；
 * <b>归档本只读</b>（归档 = 整本移走另起一本，历史仍可回看但禁止再追加/二次归档）。
 */
public interface BlackboardStore {

    /** 读取条数上限 */
    int MAX_READ_LIMIT = 100;

    /** 读取默认条数 */
    int DEFAULT_READ_LIMIT = 30;

    /**
     * 追加一条记录，返回给 LLM 看的简短结果说明。
     *
     * @param workspace 当前工作区（决定存储位置）
     * @param key       记录本隔离键（通常是父会话 id）
     * @param author    登记者名（由调用方从运行时上下文解析，不接受 LLM 传入）
     * @param type      条目类型（调用方已做白名单校验）
     * @param content   正文（非空，由调用方校验）
     * @return 形如 {@code ✅ #12 已登记（risk, by main）} 的说明；失败时为可读的失败原因
     */
    String append(WorkspaceContext workspace, String key, String author, String type, String content);

    /**
     * 读取最近 {@code limit} 条记录（按 seq 升序返回，便于按时间顺序阅读）。
     *
     * @param limit 条数；{@code <= 0} 用默认值，超过 {@link #MAX_READ_LIMIT} 取上限
     */
    List<BlackboardEntry> read(WorkspaceContext workspace, String key, int limit);

    /**
     * 列出该工作区下所有记录本（含归档本），按最后修改时间倒序。
     * <p>
     * 供管理页面浏览用：{@link #read} 只能按已知 key 取单个记录本，而页面需要先知道
     * 「这个工作区里有哪些记录本」。活跃本的 key 是存储侧的记录本键（通常是会话 id）；
     * 归档本的 key 是 {@code <基础key>.archived-<时间戳>} 完整唯一主干，可直接回传给
     * {@link #read} 定位归档件。
     * <p>
     * 归档本 {@code archived=true} 且只读（可回看条目，不能 append / 再次归档）。
     */
    List<BlackboardBook> listBooks(WorkspaceContext workspace);

    /**
     * 归档指定记录本：整本移出活跃区（本地为改名 {@code <key>.archived-<时间戳>.jsonl}，
     * cloud 为 hub 侧把全部行 book_key 改为 {@code <key>.archived-<epochMillis>}），
     * 记录本随之归零（下次 append 从 #1 重新开始）。
     * <p>
     * <b>为什么是归档而不是删除：</b>blackboard 的核心价值是「不可抵赖的 append-only 轨迹」，
     * 真删除会破坏这一性质。归档保留了全部历史，出问题仍可回溯，
     * 同时达到「下次任务从干净的黑板开始」的目的。
     * <p>
     * <b>为什么不提供给 AI 工具：</b>并行子 Agent 场景下，若 AI 能自行清空黑板，
     * 可能在「觉得记录太乱」时把同伴正在依赖的结论抹掉 —— 这是灾难性的。
     * 故归档仅由人工经管理页面触发。
     *
     * @return 归档后的记录本键；记录本不存在时返回 null
     * @throws IllegalArgumentException 该 key 已是归档本（不允许二次归档）
     */
    String archiveBook(WorkspaceContext workspace, String key);

    /**
     * 一个记录本的概要（管理页面列表用）。
     *
     * @param key          记录本键：活跃本为记录本键（通常是会话 id）；
     *                     归档本为 {@code <基础key>.archived-<时间戳>} 完整唯一主干
     * @param entries      有效条目数
     * @param lastModified 最后修改时间（epoch millis）
     * @param archived     是否为归档本（归档本只读，可回看）
     * @param archivedAt   归档时间（epoch millis）；活跃本为 0
     */
    record BlackboardBook(String key, long entries, long lastModified,
                          boolean archived, long archivedAt) {
    }
}
