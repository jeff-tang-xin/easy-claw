package com.xinl.easyclaw.knowledge;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalKnowledgeService} 的行为约束。
 * <p>
 * 关键约束是 <b>KNOWLEDGE.md 索引不能重复膨胀</b>：该文件被框架
 * {@code WorkspaceContextMiddleware} 每请求全文注入系统提示，
 * 若同名条目反复写入导致索引行重复累积，会持续侵蚀上下文预算。
 */
@DisplayName("本地知识库")
class LocalKnowledgeServiceTest {

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("ws-test").path(root).build();
    }

    private Path kbDir(Path root) {
        return root.resolve(".easyClaw").resolve("agent").resolve("knowledge");
    }

    @Test
    @DisplayName("目录不存在时列表为空而不抛异常")
    void missingDirReturnsEmpty(@TempDir Path root) {
        assertEquals(List.of(), new LocalKnowledgeService().list(ws(root)));
    }

    @Test
    @DisplayName("写入后可读回，且生成 KNOWLEDGE.md 索引")
    void writeThenRead(@TempDir Path root) throws IOException {
        LocalKnowledgeService svc = new LocalKnowledgeService();

        String result = svc.write("rag-design", "RAG 方案取舍", "# 正文\n本地优先。", ws(root));
        assertTrue(result.startsWith("✅"), "写入应成功；实际: " + result);

        assertTrue(svc.exists("rag-design", ws(root)));
        String content = svc.read("rag-design", ws(root));
        assertTrue(content.contains("本地优先。"), "应能读回正文");
        assertTrue(content.contains("summary: RAG 方案取舍"), "YAML 头应含摘要");

        Path index = kbDir(root).resolve("KNOWLEDGE.md");
        assertTrue(Files.exists(index), "索引文件必须生成，否则框架注入不到任何内容");
        assertTrue(Files.readString(index, StandardCharsets.UTF_8).contains("[rag-design](rag-design.md)"),
                "索引应含条目链接");
    }

    @Test
    @DisplayName("同名重复写入时索引不重复累积")
    void indexNotDuplicatedOnOverwrite(@TempDir Path root) throws IOException {
        LocalKnowledgeService svc = new LocalKnowledgeService();
        svc.write("env-notes", "初版摘要", "v1", ws(root));
        svc.write("env-notes", "修订摘要", "v2", ws(root));
        svc.write("env-notes", "最终摘要", "v3", ws(root));

        String index = Files.readString(kbDir(root).resolve("KNOWLEDGE.md"), StandardCharsets.UTF_8);

        long occurrences = index.lines().filter(l -> l.contains("(env-notes.md)")).count();
        assertEquals(1, occurrences,
                "同一条目在索引中只应出现一次，否则每请求注入的索引会无限膨胀；实际索引:\n" + index);
        assertTrue(index.contains("最终摘要"), "索引摘要应更新为最新值");
        assertFalse(index.contains("初版摘要"), "旧摘要不应残留");

        assertTrue(svc.read("env-notes", ws(root)).contains("v3"), "正文应被最新内容覆盖");
        assertEquals(1, svc.list(ws(root)).size(), "覆盖写不应产生多个条目");
    }

    @Test
    @DisplayName("KNOWLEDGE.md 自身不被当作知识条目列出")
    void indexFileNotListedAsEntry(@TempDir Path root) {
        LocalKnowledgeService svc = new LocalKnowledgeService();
        svc.write("topic-a", "摘要 A", "内容", ws(root));

        List<KnowledgeEntry> entries = svc.list(ws(root));

        assertEquals(1, entries.size(), "索引文件是元数据，不应混进条目清单；实际: " + entries);
        assertEquals("topic-a", entries.get(0).topic());
        assertEquals("摘要 A", entries.get(0).summary(), "列表应能解析出 YAML 头里的摘要");
    }

    @Test
    @DisplayName("topic 中的路径穿越字符被消毒，文件不会逃出知识库目录")
    void topicPathTraversalSanitized(@TempDir Path root) {
        LocalKnowledgeService svc = new LocalKnowledgeService();

        svc.write("..\\..\\evil", "越权尝试", "x", ws(root));

        Path escaped = root.resolve(".easyClaw").resolve("evil.md");
        assertFalse(Files.exists(escaped), "消毒失败会让 AI 得到向知识库外任意写文件的能力");
        assertTrue(Files.isDirectory(kbDir(root)), "文件应仍落在 knowledge 目录内");
    }

    @Test
    @DisplayName("空 topic / 空内容被拒绝")
    void blankInputsRejected(@TempDir Path root) {
        LocalKnowledgeService svc = new LocalKnowledgeService();
        assertTrue(svc.write("  ", "s", "c", ws(root)).startsWith("❌"));
        assertTrue(svc.write("t", "  ", "c", ws(root)).startsWith("❌"));
        assertTrue(svc.write("t", "s", "  ", ws(root)).startsWith("❌"));
        assertEquals("", svc.read("nope", ws(root)), "不存在的条目应返回空串而非抛异常");
    }
}