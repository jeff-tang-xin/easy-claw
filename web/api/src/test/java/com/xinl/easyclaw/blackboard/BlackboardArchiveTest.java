package com.xinl.easyclaw.blackboard;

import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlackboardStore#archiveBook} 的行为约束。
 * <p>
 * 归档是「清空」机制的实现方式：改名而非删除，保住 append-only 轨迹的可回溯性。
 * 页面依赖三点：① 归档后原记录本从清单消失；② 历史内容仍在磁盘上；
 * ③ 归档后重新登记的 seq 从 1 重新开始（否则用户会看到「清空后第一条是 #106」）。
 */
@DisplayName("记录本归档清空")
class BlackboardArchiveTest {

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("ws-test").path(root).build();
    }

    private Path bbDir(Path root) {
        return root.resolve(".easyClaw").resolve("agent").resolve("blackboard");
    }

    @Test
    @DisplayName("记录本不存在时返回 null，不抛异常")
    void missingBookReturnsNull(@TempDir Path root) {
        assertNull(new BlackboardStore().archiveBook(ws(root), "nope"));
    }

    @Test
    @DisplayName("归档后原文件消失、归档文件保留原内容")
    void archivePreservesContent(@TempDir Path root) throws IOException {
        BlackboardStore store = new BlackboardStore();
        store.append(ws(root), "s1", "main", "note", "保留我");

        Path original = bbDir(root).resolve("s1.jsonl");
        assertTrue(Files.exists(original), "前置条件：记录本应已创建");

        String archivedName = store.archiveBook(ws(root), "s1");

        assertNotNull(archivedName);
        assertTrue(archivedName.startsWith("s1.archived-"), "归档名应带 archived- 前缀标记");
        assertFalse(Files.exists(original), "归档后原记录本文件应已移走");

        Path archived = bbDir(root).resolve(archivedName);
        assertTrue(Files.exists(archived), "归档文件应存在（数据不丢）");
        assertTrue(Files.readString(archived, StandardCharsets.UTF_8).contains("保留我"),
                "归档文件应完整保留原内容");
    }

    @Test
    @DisplayName("归档后 seq 从 1 重新开始")
    void seqRestartsAfterArchive(@TempDir Path root) {
        BlackboardStore store = new BlackboardStore();
        store.append(ws(root), "s1", "main", "note", "第一条");
        store.append(ws(root), "s1", "main", "note", "第二条");

        store.archiveBook(ws(root), "s1");

        String result = store.append(ws(root), "s1", "main", "note", "归档后第一条");
        assertTrue(result.contains("#1"),
                "归档等于清空，序号必须重置，否则用户看到的编号会莫名其妙地延续旧值；实际: " + result);

        List<BlackboardEntry> entries = store.read(ws(root), "s1", 10);
        assertEquals(1, entries.size(), "归档后记录本应只剩新登记的一条");
        assertEquals("归档后第一条", entries.get(0).content());
    }

    @Test
    @DisplayName("归档文件不出现在记录本清单里")
    void archivedFilesExcludedFromList(@TempDir Path root) {
        BlackboardStore store = new BlackboardStore();
        store.append(ws(root), "s1", "main", "note", "x");
        store.archiveBook(ws(root), "s1");

        List<BlackboardStore.BlackboardBook> books = store.listBooks(ws(root));

        assertTrue(books.isEmpty(),
                "归档件是历史快照，不应作为活跃记录本出现在清单中，否则用户会看到一堆 .archived- 条目；实际: " + books);
    }
}