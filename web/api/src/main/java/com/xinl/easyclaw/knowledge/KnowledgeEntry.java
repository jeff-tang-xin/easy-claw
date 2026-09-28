package com.xinl.easyclaw.knowledge;

/**
 * 知识条目（从记录本晋升或人工创建的知识文件）。
 *
 * @param topic       条目名（也即文件名去后缀，全局唯一，扁平命名）
 * @param summary     摘要（显示在 KNOWLEDGE.md 索引中）
 * @param lastModified 最后修改时间（epoch millis）
 * @param fileSize    文件大小（字节）
 */
public record KnowledgeEntry(String topic, String summary, long lastModified, long fileSize) {
}