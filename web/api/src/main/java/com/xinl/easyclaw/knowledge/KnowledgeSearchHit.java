package com.xinl.easyclaw.knowledge;

/**
 * 知识库搜索命中项（{@link KnowledgeService#search} 的返回单元）。
 *
 * @param topic   命中条目名（也即文件名去后缀）
 * @param summary 条目摘要（来自 md 文件的 YAML front matter）
 * @param snippet 命中上下文片段：首个正文命中行及其上下各 1 行，约 200 字符截断；
 *                仅条目名/摘要命中而正文未命中时回退为摘要
 */
public record KnowledgeSearchHit(String topic, String summary, String snippet) {
}
