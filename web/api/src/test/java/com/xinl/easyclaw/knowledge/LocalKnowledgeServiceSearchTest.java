package com.xinl.easyclaw.knowledge;

import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalKnowledgeService#search} 的行为约束：
 * 多词 AND、大小写不敏感、权重排序（条目名 &gt; 摘要 &gt; 正文）、
 * 无命中、空 query、limit 截断、单文件异常容错、片段截断。
 */
@DisplayName("本地知识库全文搜索")
class LocalKnowledgeServiceSearchTest {

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("ws-test").path(root).build();
    }

    private Path kbDir(Path root) throws Exception {
        Path dir = root.resolve(".easyClaw").resolve("agent").resolve("knowledge");
        Files.createDirectories(dir);
        return dir;
    }

    private void writeEntry(Path root, String topic, String summary, String... bodyLines) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("topic: ").append(topic).append("\n");
        sb.append("summary: ").append(summary).append("\n");
        sb.append("created: 2026-09-09T00:00:00+08:00\n");
        sb.append("updated: 2026-09-09T00:00:00+08:00\n");
        sb.append("---\n\n");
        for (String line : bodyLines) {
            sb.append(line).append("\n");
        }
        Files.writeString(kbDir(root).resolve(topic + ".md"), sb.toString());
    }

    @Test
    @DisplayName("多词搜索是 AND：任一词不命中即整文件不命中")
    void requiresAllTerms(@TempDir Path root) throws Exception {
        writeEntry(root, "java-notes", "构建笔记", "Java 21 与 Maven 构建");
        writeEntry(root, "maven-notes", "Maven 技巧", "Maven 使用技巧");

        // java-notes 两词皆中；maven-notes 只有 maven（条目名），缺 java → AND 排除
        List<KnowledgeSearchHit> hits = new LocalKnowledgeService().search("java maven", 10, ws(root));
        assertEquals(1, hits.size());
        assertEquals("java-notes", hits.get(0).topic());
    }

    @Test
    @DisplayName("匹配不区分大小写")
    void caseInsensitive(@TempDir Path root) throws Exception {
        writeEntry(root, "java-notes", "构建笔记", "Java 21 与 Maven 构建");

        List<KnowledgeSearchHit> hits = new LocalKnowledgeService().search("JAVA MAVEN", 10, ws(root));
        assertEquals(1, hits.size());
        assertEquals("java-notes", hits.get(0).topic());
    }

    @Test
    @DisplayName("权重排序：条目名命中 > 摘要命中 > 正文命中；snippet 正文未命中时回退为摘要")
    void ordersByWeight(@TempDir Path root) throws Exception {
        writeEntry(root, "alpha-guide", "不含关键词的摘要", "正文也没有关键词");
        writeEntry(root, "beta-notes", "关于 alpha 的摘要", "正文普通内容");
        writeEntry(root, "gamma-notes", "无关摘要", "前文一行", "这里提到 alpha 一次", "后文一行");

        List<KnowledgeSearchHit> hits = new LocalKnowledgeService().search("alpha", 10, ws(root));
        assertEquals(3, hits.size());
        assertEquals("alpha-guide", hits.get(0).topic(), "条目名命中权重最高");
        assertEquals("beta-notes", hits.get(1).topic(), "摘要命中次之");
        assertEquals("gamma-notes", hits.get(2).topic(), "仅正文命中最低");

        // 条目名命中但正文未命中时，snippet 回退为 summary
        assertEquals("不含关键词的摘要", hits.get(0).snippet());
        // 正文命中时，snippet 含首个命中行及上下各 1 行
        assertTrue(hits.get(2).snippet().contains("前文一行"), "片段应含命中行上一行");
        assertTrue(hits.get(2).snippet().contains("这里提到 alpha 一次"), "片段应含命中行");
        assertTrue(hits.get(2).snippet().contains("后文一行"), "片段应含命中行下一行");
    }

    @Test
    @DisplayName("无命中时返回空列表")
    void emptyWhenNothingMatches(@TempDir Path root) throws Exception {
        writeEntry(root, "java-notes", "构建笔记", "Java 21 与 Maven 构建");

        LocalKnowledgeService svc = new LocalKnowledgeService();
        assertTrue(svc.search("不存在的词xyz", 10, ws(root)).isEmpty());
        assertTrue(svc.search("java python", 10, ws(root)).isEmpty(), "多词 AND：任一词不命中即整体不命中");
    }

    @Test
    @DisplayName("空 query 返回空列表而不抛异常")
    void emptyWhenQueryBlank(@TempDir Path root) throws Exception {
        writeEntry(root, "java-notes", "构建笔记", "Java 21 与 Maven 构建");

        LocalKnowledgeService svc = new LocalKnowledgeService();
        assertTrue(svc.search(null, 10, ws(root)).isEmpty());
        assertTrue(svc.search("", 10, ws(root)).isEmpty());
        assertTrue(svc.search("   ", 10, ws(root)).isEmpty());
    }

    @Test
    @DisplayName("limit 截断；≤0 时按默认 10 处理")
    void honorsLimit(@TempDir Path root) throws Exception {
        writeEntry(root, "entry-a", "摘要 a", "包含 common 的正文");
        writeEntry(root, "entry-b", "摘要 b", "包含 common 的正文");
        writeEntry(root, "entry-c", "摘要 c", "包含 common 的正文");

        LocalKnowledgeService svc = new LocalKnowledgeService();
        assertEquals(2, svc.search("common", 2, ws(root)).size());
        assertEquals(3, svc.search("common", 0, ws(root)).size(), "limit=0 走默认 10，3 条全部返回");
        assertEquals(3, svc.search("common", -1, ws(root)).size(), "limit<0 同样走默认值");
    }

    @Test
    @DisplayName("单个文件读取异常不弄死整体搜索")
    void survivesUnreadableFile(@TempDir Path root) throws Exception {
        writeEntry(root, "good-entry", "正常条目", "包含 keyword 的正文");
        // 目录伪装成 .md 文件：能通过文件名过滤，但 readAllLines 抛 IOException
        Files.createDirectories(kbDir(root).resolve("bad.md"));

        List<KnowledgeSearchHit> hits = new LocalKnowledgeService().search("keyword", 10, ws(root));
        assertEquals(1, hits.size());
        assertEquals("good-entry", hits.get(0).topic());
    }

    @Test
    @DisplayName("命中片段约 200 字符截断")
    void truncatesSnippet(@TempDir Path root) throws Exception {
        String longLine = "前缀" + "很".repeat(300) + "keyword" + "长".repeat(300);
        writeEntry(root, "long-entry", "长行条目", longLine);

        List<KnowledgeSearchHit> hits = new LocalKnowledgeService().search("keyword", 10, ws(root));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).snippet().length() <= 201, "片段应截断到 200 字符 + 省略号");
        assertTrue(hits.get(0).snippet().endsWith("…"));
    }
}
