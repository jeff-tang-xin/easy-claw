package com.xinl.easyclaw.api;

import com.xinl.easyclaw.knowledge.KnowledgeEntry;
import com.xinl.easyclaw.knowledge.KnowledgeService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理端点：浏览、读取、删除知识条目。
 * <p>
 * 写入操作仅限 AI 通过 {@code knowledge_write} 工具完成，
 * 人工编辑应在管理页面修改后保存。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;
    private final WorkspaceAccessGuard accessGuard;

    public KnowledgeController(KnowledgeService knowledgeService, WorkspaceAccessGuard accessGuard) {
        this.knowledgeService = knowledgeService;
        this.accessGuard = accessGuard;
    }

    /**
     * 列出该工作区所有知识条目（按最后修改时间倒序）。
     */
    @GetMapping("/entries")
    public List<KnowledgeEntry> listEntries(@RequestParam String workspaceId) {
        WorkspaceContext ws = accessGuard.checkWorkspace(workspaceId);
        return knowledgeService.list(ws);
    }

    /**
     * 读取指定知识条目的全文。
     */
    @GetMapping("/entry")
    public Map<String, String> getEntry(@RequestParam String workspaceId,
                                        @RequestParam String topic) {
        WorkspaceContext ws = accessGuard.checkWorkspace(workspaceId);
        String content = knowledgeService.read(topic, ws);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("条目「" + topic + "」不存在");
        }
        return Map.of("topic", topic, "content", content);
    }
}