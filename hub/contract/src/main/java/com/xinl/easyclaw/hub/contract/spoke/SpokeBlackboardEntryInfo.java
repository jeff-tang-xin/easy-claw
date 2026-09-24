package com.xinl.easyclaw.hub.contract.spoke;

/** spoke 工作区黑板条目（GET /api/spoke/blackboard/entries，按 seq 升序）。 */
public record SpokeBlackboardEntryInfo(
        Long seq,
        String ts,
        String author,
        String type,
        String content) {
}
