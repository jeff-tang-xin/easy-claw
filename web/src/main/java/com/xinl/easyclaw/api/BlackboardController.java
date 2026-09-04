package com.xinl.easyclaw.api;

import com.xinl.easyclaw.blackboard.BlackboardEntry;
import com.xinl.easyclaw.blackboard.BlackboardStore;
import com.xinl.easyclaw.blackboard.BlackboardStore.BlackboardBook;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 共享记录本（blackboard）管理端点。
 * <p>
 * 条目本身<b>只读</b>：blackboard 是 append-only 且只允许 AI 通过工具写入（它们被沙箱限制在
 * .easyClaw 目录外，只能经 blackboard_append 工具操作）。人工编辑不应绕过此限制。
 * 如需更正某条内容，应先在黑板上追加一条勘误。
 * <p>
 * 唯一的写操作是<b>整本归档</b>（{@link #archiveBook}）：它不修改任何条目，
 * 只把整个记录本移走以便重新开始。该操作刻意<b>不提供给 AI</b> ——
 * 并行子 Agent 若能自行清空黑板，会抹掉同伴正在依赖的结论。
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

    /**
     * 归档指定记录本：把当前 jsonl 重命名为 {@code <key>.archived-<时间戳>.jsonl}，
     * 记录本随之归零（下次访问自动新建空文件）。
     * <p>
     * 本操作<strong>不可逆</strong>：归档后该记录本不再出现在书列表中，
     * 其内容不可再通过 blackboard API 读取。如需恢复，需手动在文件系统中改名。
     * <p>
     * <b>为什么不是删除：</b>保留历史以便回溯，改名即达「清空」效果。
     *
     * @return 归档后的文件名（不含路径）
     */
    @PostMapping("/archive")
    public Map<String, Object> archiveBook(@RequestParam String workspaceId,
                                           @RequestParam String key) {
        WorkspaceContext ws = accessGuard.checkWorkspace(workspaceId);
        String archivedName = blackboardStore.archiveBook(ws, key);
        if (archivedName == null) {
            throw new IllegalArgumentException("记录本「" + key + "」不存在");
        }
        log.info("记录本已归档: workspace={}, key={}, archived={}", workspaceId, key, archivedName);
        return Map.of("success", true, "archived", archivedName, "message", "记录本已归档并清空");
    }
}