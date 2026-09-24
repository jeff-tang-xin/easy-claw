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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlackboardStore#archiveBook} 的行为约束。
 * <p>
 * 归档是「清空活跃本」机制的实现方式：改名而非删除，保住 append-only 轨迹的可回溯性。
 * 契约四点：① 归档后活跃文件移走，但归档本仍出现在清单中（{@code archived=true}），
 * 可用其完整句柄 key 只读回看；② 历史内容原样保留在归档文件上；
 * ③ 归档后重新登记的 seq 从 1 重新开始（否则用户会看到「清空后第一条是 #106」）；
 * ④ 归档本只读：拒绝 append 与二次归档。
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
        assertNull(new LocalBlackboardStore().archiveBook(ws(root), "nope"));
    }

    @Test
    @DisplayName("归档后原文件消失、归档文件保留原内容")
    void archivePreservesContent(@TempDir Path root) throws IOException {
        BlackboardStore store = new LocalBlackboardStore();
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
        BlackboardStore store = new LocalBlackboardStore();
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
    @DisplayName("归档后归档本仍出现在清单，带 archived 标记与归档时间，且可只读回看")
    void archivedBookRemainsVisibleAndReadable(@TempDir Path root) throws IOException {
        BlackboardStore store = new LocalBlackboardStore();
        store.append(ws(root), "s1", "main", "note", "历史内容");
        String archivedName = store.archiveBook(ws(root), "s1");

        List<BlackboardStore.BlackboardBook> books = store.listBooks(ws(root));
        assertEquals(1, books.size(), "归档本不应被清单过滤掉；实际: " + books);
        BlackboardStore.BlackboardBook archived = books.get(0);
        String archivedKey = archivedName.substring(0, archivedName.length() - ".jsonl".length());
        assertEquals(archivedKey, archived.key(), "归档本 key 应为含 .archived- 的完整唯一主干");
        assertTrue(archived.archived(), "归档本必须带 archived=true 标记");
        assertTrue(archived.archivedAt() > 0, "归档本应能反解出归档时间");
        assertEquals(1, archived.entries(), "归档本条目数应保留");

        List<BlackboardEntry> entries = store.read(ws(root), archivedKey, 100);
        assertEquals(1, entries.size(), "归档本应仍可通过其句柄回看历史条目");
        assertEquals("历史内容", entries.get(0).content());
    }

    @Test
    @DisplayName("活跃本与归档本同基础 key 时并存且各自可定位")
    void activeAndArchivedCoexist(@TempDir Path root) {
        BlackboardStore store = new LocalBlackboardStore();
        store.append(ws(root), "s1", "main", "note", "旧");
        store.archiveBook(ws(root), "s1");
        store.append(ws(root), "s1", "main", "note", "新");

        List<BlackboardStore.BlackboardBook> books = store.listBooks(ws(root));
        assertEquals(2, books.size(), "应有一本归档 + 一本活跃；实际: " + books);

        // 活跃本读到新内容
        List<BlackboardEntry> active = store.read(ws(root), "s1", 100);
        assertEquals(1, active.size());
        assertEquals("新", active.get(0).content());

        // 归档本读到旧内容
        String archivedKey = books.stream()
                .filter(BlackboardStore.BlackboardBook::archived)
                .findFirst().orElseThrow().key();
        List<BlackboardEntry> archivedEntries = store.read(ws(root), archivedKey, 100);
        assertEquals(1, archivedEntries.size());
        assertEquals("旧", archivedEntries.get(0).content());
    }

    @Test
    @DisplayName("归档本拒绝再追加（返回失败说明且内容不变）")
    void archivedBookRejectsAppend(@TempDir Path root) {
        BlackboardStore store = new LocalBlackboardStore();
        store.append(ws(root), "s1", "main", "note", "旧");
        String archivedName = store.archiveBook(ws(root), "s1");
        String archivedKey = archivedName.substring(0, archivedName.length() - ".jsonl".length());

        String result = store.append(ws(root), archivedKey, "main", "note", "试图改写历史");
        assertTrue(result.startsWith("❌"), "追加归档本必须被拒绝；实际: " + result);
        assertTrue(result.contains("只读"), "拒绝原因应说明归档本只读；实际: " + result);

        List<BlackboardEntry> entries = store.read(ws(root), archivedKey, 100);
        assertEquals(1, entries.size(), "被拒绝的追加不得落盘，归档内容必须保持不变");
        assertEquals("旧", entries.get(0).content());
    }

    @Test
    @DisplayName("归档本拒绝二次归档")
    void archivedBookRejectsReArchive(@TempDir Path root) {
        BlackboardStore store = new LocalBlackboardStore();
        store.append(ws(root), "s1", "main", "note", "旧");
        String archivedName = store.archiveBook(ws(root), "s1");
        String archivedKey = archivedName.substring(0, archivedName.length() - ".jsonl".length());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.archiveBook(ws(root), archivedKey),
                "对归档本再次归档必须被拒绝");
        assertTrue(ex.getMessage().contains("已归档"), "拒绝原因应说明已归档；实际: " + ex.getMessage());
    }
}