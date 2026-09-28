package com.xinl.easyclaw.knowledge;

import com.xinl.easyclaw.workspace.WorkspaceContext;

import java.util.List;

/**
 * 知识库服务接口：本地 knowledge/ 目录的读写与管理。
 * <p>
 * 支持双模架构：
 * <ul>
 *   <li><b>本地模式</b>（默认）：文件 wiki，内容直接写入 {@code knowledge/<topic>.md}，
 *       自动维护 {@code KNOWLEDGE.md} 索引。</li>
 *   <li><b>远程模式</b>（预留）：通过 SPI 或配置接入向量数据库/外部 RAG 服务。</li>
 * </ul>
 * 当前实现为 {@link LocalKnowledgeService}。
 */
public interface KnowledgeService {

    /**
     * 写入知识条目（创建或覆盖）。
     * <p>
     * 若 topic 已存在，全文覆盖并更新 {@code KNOWLEDGE.md} 索引；
     * 若不存在，创建新文件并追加索引。
     *
     * @param topic     条目名（全局唯一，仅含字母数字与 {@code - _}）
     * @param summary   摘要（显示在索引中，一句话说清内容）
     * @param content   正文（Markdown）
     * @param workspace 当前工作区
     * @return 操作结果说明
     */
    String write(String topic, String summary, String content, WorkspaceContext workspace);

    /**
     * 列出当前工作区所有知识条目（按最后修改时间倒序）。
     */
    List<KnowledgeEntry> list(WorkspaceContext workspace);

    /**
     * 读取指定知识条目的全文。
     *
     * @param topic     条目名
     * @param workspace 当前工作区
     * @return 文件内容；条目不存在时返回空字符串
     */
    String read(String topic, WorkspaceContext workspace);

    /**
     * 是否存在指定 topic 的条目。
     */
    boolean exists(String topic, WorkspaceContext workspace);

    /**
     * 全文搜索知识库（子串匹配档）。
     * <p>
     * query 按空白拆词，所有词必须全部命中（AND 语义），匹配不区分大小写；
     * 匹配范围 = 条目名 + 摘要（md 文件的 YAML front matter）+ 正文。
     * 结果按权重排序：条目名命中 &gt; 摘要命中 &gt; 正文命中（同权重内保持文件遍历序）。
     * <p>
     * 单个文件读取异常不会导致整体搜索失败（仿 {@link #list} 的容错）。
     * <p>
     * 远程模式（向量数据库/外部 RAG）可改写本方法为语义检索，签名不变。
     *
     * @param query     搜索词，多个词用空白分隔；空或全空白时返回空列表
     * @param limit     最多返回条数；≤0 时按默认值 10 处理
     * @param workspace 当前工作区
     * @return 命中清单，按权重降序
     */
    List<KnowledgeSearchHit> search(String query, int limit, WorkspaceContext workspace);
}