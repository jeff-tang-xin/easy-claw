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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlackboardStore#listBooks} 的行为约束。
 * <p>
 * 该方法是管理页面 {@code GET /api/blackboard/books} 的唯一数据来源，
 * 页面依赖它「目录缺失也不抛异常」与「按修改时间倒序」两点。
 */
@DisplayName("记录本清单枚举")
class BlackboardListBooksTest {

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("ws-test").path(root).build();
    }

    private Path bbDir(Path root) {
        return root.resolve(".easyClaw").resolve("agent").resolve("blackboard");
    }

    @Test
    @DisplayName("目录不存在时返回空列表而不抛异常")
    void missingDirReturnsEmpty(@TempDir Path root) {
        assertEquals(List.of(), new BlackboardStore().listBooks(ws(root)));
    }

    @Test
    @DisplayName("只收录 .jsonl 文件，并统计条目数")
    void listsOnlyJsonlWithCounts(@TempDir Path root) throws IOException {
        Path dir = bbDir(root);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("session-a.jsonl"),
                "{\"seq\":1,\"ts\":\"t\",\"author\":\"main\",\"type\":\"note\",\"content\":\"x\"}\n"
                        + "{\"seq\":2,\"ts\":\"t\",\"author\":\"main\",\"type\":\"risk\",\"content\":\"y\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("notes.txt"), "ignored", StandardCharsets.UTF_8);

        List<BlackboardStore.BlackboardBook> books = new BlackboardStore().listBooks(ws(root));

        assertEquals(1, books.size(), "非 .jsonl 文件不应被收录");
        assertEquals("session-a", books.get(0).key(), "key 应为文件名去掉 .jsonl 后缀");
        assertEquals(2, books.get(0).entries());
    }

    @Test
    @DisplayName("多个记录本按最后修改时间倒序")
    void sortedByLastModifiedDesc(@TempDir Path root) throws IOException {
        Path dir = bbDir(root);
        Files.createDirectories(dir);
        Path older = dir.resolve("old.jsonl");
        Path newer = dir.resolve("new.jsonl");
        Files.writeString(older, "{\"seq\":1}\n", StandardCharsets.UTF_8);
        Files.writeString(newer, "{\"seq\":1}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(9_000_000L));

        List<BlackboardStore.BlackboardBook> books = new BlackboardStore().listBooks(ws(root));

        assertEquals(2, books.size());
        assertEquals("new", books.get(0).key(), "最近修改的记录本应排在最前");
        assertTrue(books.get(0).lastModified() > books.get(1).lastModified());
    }
}