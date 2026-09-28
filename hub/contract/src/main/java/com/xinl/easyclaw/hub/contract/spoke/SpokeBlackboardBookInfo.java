package com.xinl.easyclaw.hub.contract.spoke;

/** spoke 工作区黑板本聚合项（GET /api/spoke/blackboard/books）：lastModified/archivedAt 为 epoch 毫秒（archivedAt 解析失败为 null）。 */
public record SpokeBlackboardBookInfo(
        String key,
        Long entryCount,
        Long lastModified,
        Boolean archived,
        Long archivedAt) {
}
