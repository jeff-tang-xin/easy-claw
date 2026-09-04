package com.xinl.easyclaw.api;

import com.xinl.easyclaw.blackboard.BlackboardEntry;
import com.xinl.easyclaw.blackboard.BlackboardStore;
import com.xinl.easyclaw.blackboard.BlackboardStore.BlackboardBook;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 共享记录本（blackboard）只读端点。
 * <p>
 * 不提供写操作：blackboard 是 append-only 且只允许 AI 通过工具写入（它们被沙箱限制在
 * .easyClaw 目录外，只能经 blackboard_append 工具操作）。人工编辑不应绕过此限制。
 * 如需删除/修改条目，应先在黑板上追加一条勘误。
 */
@RestController
@RequestMapping("/api/blackboard")
public class BlackboardController {

    private static final Logger log = LoggerFactory.getLogger(BlackboardController.class);

    private final BlackboardStore blackboardStore;
    private final WorkspaceAccessGuard accessGuard;

    public BlackboardController(BlackboardStore blackboardStore, WorkspaceAccessGuard accessGuard) {
        this.blackboardStore = blackboardStore;
        this.accessGuard = accessGuard;
    }

    /**
     * 列出该工作区所有记录本（key + 条目数 + 最后修改时间），按修改时间倒序。
     * 目录不存在或为空时返回空列表。
     */
    @GetMapping("/books")
    public List<BlackboardBook> listBooks(@RequestParam String workspaceId) {
        WorkspaceContext ws = accessGuard.checkWorkspace(workspaceId);
        return blackboardStore.listBooks(ws);
    }

    /**
     * 读取指定记录本的条目列表（按 seq 升序，最近优先）。
     *
     * @param workspaceId 工作区 ID
     * @param key         记录本键（由 listBooks 返回，或由后端告知的会话 id）
     * @param limit       条数上限（默认 30，最大 100）
     */
    @GetMapping("/entries")
    public List<BlackboardEntry> getEntries(@RequestParam String workspaceId,
                                            @RequestParam String key,
                                            @RequestParam(defaultValue = "30") int limit) {
        WorkspaceContext ws = accessGuard.checkWorkspace(workspaceId);
        return blackboardStore.read(ws, key, limit);
    }
}