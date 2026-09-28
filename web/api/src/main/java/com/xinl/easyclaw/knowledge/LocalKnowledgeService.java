package com.xinl.easyclaw.knowledge;

import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 本地知识库实现：文件 wiki 式存储。
 * <p>
 * 文件位置：{@code <workspace>/.easyClaw/agent/knowledge/<topic>.md}
 * 索引文件：{@code KNOWLEDGE.md}（自动维护，供 harness 注入系统提示）
 * <p>
 * <b>为什么文件 wiki 而不是建表：</b>框架的 {@code WorkspaceContextMiddleware}
 * 自动读取 {@code knowledge/KNOWLEDGE.md} 全文注入系统提示，建表不会被框架消费。
 * 文件 wiki 可被框架「免费」注入，同时方便人工编辑。
 * <p>
 * <b>并发模型：</b>同一 JVM 内按 topic 分段 {@link ReentrantLock}，
 * 保证 topic 文件与 KNOWLEDGE.md 索引的一致性。
 * 刻意不用 {@code FileLock}：本系统只有一个进程写，跨进程锁在 Windows 上还会带来释放不及时的麻烦。
 * <p>
 * <b>安全边界：</b>{@code knowledge/} 目录位于 {@code .easyClaw} 下，
 * 该目录在 {@code forbidden-paths} 内，AI 无法用文件工具直读或篡改，
 * 只能经由 knowledge 工具访问 —— 这正是想要的：保证知识库的写入经过格式校验与索引维护。
 * <p>
 * <b>扁平命名：</b>所有 topic 直接作为文件名，支持用 {@code -} 前缀做分类命名空间
 * （如 {@code architecture-overview}、{@code rag-design}），不建子文件夹。
 * 这样 {@code listKnowledgeFiles} 的 {@code Files.walk} 递归深度始终为 1，
 * 注入 token 开销最小，且去重可靠（文件名唯一 == 条目唯一）。
 */
@Service
public class LocalKnowledgeService implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeService.class);

    /** 知识库目录名 */
    private static final String KNOWLEDGE_DIR = "knowledge";
    /** 索引文件名（框架约定：注入系统提示） */
    private static final String KNOWLEDGE_MD = "KNOWLEDGE.md";
    /** 单条内容上限（字符）：超出截断 */
    private static final int MAX_CONTENT_CHARS = 50_000;
    /** 搜索默认返回条数：limit ≤ 0 时启用 */
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    /** 搜索片段上限（字符）：超出截断 */
    private static final int SNIPPET_MAX_CHARS = 200;

    /** topic → 写锁（同 JVM 内串行化同一 topic 的写入） */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public String write(String topic, String summary, String content, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return "❌ topic 不能为空。";
        }
        if (summary == null || summary.isBlank()) {
            return "❌ summary 不能为空。";
        }
        if (content == null || content.isBlank()) {
            return "❌ content 不能为空。";
        }
        String safeTopic = safeTopic(topic);
        String body = truncate(content);

        ReentrantLock lock = locks.computeIfAbsent(safeTopic, k -> new ReentrantLock());
        lock.lock();
        try {
            Path dir = knowledgeDir(workspace);
            Files.createDirectories(dir);

            Path file = dir.resolve(safeTopic + ".md");
            String header = "---\n"
                    + "topic: " + safeTopic + "\n"
                    + "summary: " + summary + "\n"
                    + "created: " + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                    + "---\n\n";
            Files.writeString(file, header + body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            updateIndex(dir, safeTopic, summary);

            return "✅ 知识已写入 `" + safeTopic + ".md`（" + safeTopic + "）"
                    + (body.length() < content.length() ? "；正文过长已截断" : "");
        } catch (IOException e) {
            log.warn("写入知识条目失败: {}, {}", safeTopic, e.getMessage());
            return "❌ 写入失败：" + e.getMessage();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<KnowledgeEntry> list(WorkspaceContext workspace) {
        Path dir = knowledgeDir(workspace);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<KnowledgeEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".md")
                            && !f.getFileName().toString().equals(KNOWLEDGE_MD)).toList()) {
                String fileName = p.getFileName().toString();
                String topic = fileName.substring(0, fileName.length() - ".md".length());
                long modified = 0L;
                long size = 0L;
                String summary = "";
                try {
                    modified = Files.getLastModifiedTime(p).toMillis();
                    size = Files.size(p);
                    summary = readSummary(p);
                } catch (IOException ignore) {
                    // 单个文件异常不应让整个列表失败
                }
                entries.add(new KnowledgeEntry(topic, summary, modified, size));
            }
        } catch (IOException e) {
            log.warn("列出知识库目录失败: {}, {}", dir, e.getMessage());
            return List.of();
        }
        entries.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return List.copyOf(entries);
    }

    @Override
    public String read(String topic, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        String safeTopic = safeTopic(topic);
        Path file = knowledgeDir(workspace).resolve(safeTopic + ".md");
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取知识条目失败: {}, {}", safeTopic, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean exists(String topic, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        String safeTopic = safeTopic(topic);
        return Files.exists(knowledgeDir(workspace).resolve(safeTopic + ".md"));
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int limit, WorkspaceContext workspace) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;
        List<String> terms = Arrays.stream(query.trim().split("\\s+"))
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        Path dir = knowledgeDir(workspace);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SearchCandidate> candidates = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".md")
                            && !f.getFileName().toString().equals(KNOWLEDGE_MD)).toList()) {
                try {
                    SearchCandidate c = matchFile(p, terms);
                    if (c != null) {
                        candidates.add(c);
                    }
                } catch (IOException e) {
                    // 单个文件异常不应让整个搜索失败
                    log.warn("搜索跳过无法读取的知识文件: {}, {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("搜索知识库目录失败: {}, {}", dir, e.getMessage());
            return List.of();
        }
        // List.sort 稳定：同权重保持文件遍历序
        candidates.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
        return candidates.stream()
                .limit(effectiveLimit)
                .map(c -> new KnowledgeSearchHit(c.topic(), c.summary(), c.snippet()))
                .toList();
    }

    // =============== 内部方法 ===============

    /** 知识库目录：{@code <workspace>/.easyClaw/agent/knowledge} */
    private Path knowledgeDir(WorkspaceContext workspace) {
        return workspace.getPath()
                .resolve(".easyClaw").resolve("agent").resolve(KNOWLEDGE_DIR)
                .normalize();
    }

    /** topic → 安全文件名：只保留字母数字与 {@code - _ /}，其余一律换成 {@code _} */
    static String safeTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder(topic.length());
        for (char c : topic.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '/' ? c : '_');
        }
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** 更新 KNOWLEDGE.md 索引 */
    private void updateIndex(Path dir, String topic, String summary) throws IOException {
        Path indexFile = dir.resolve(KNOWLEDGE_MD);
        // 收集所有条目（保持索引与磁盘一致）
        List<IndexEntry> entries = new ArrayList<>();
        boolean found = false;
        if (Files.exists(indexFile)) {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                // 跳过文件头/空行，只解析 - [topic](topic.md) — summary 格式
                String parsed = parseIndexLine(line);
                if (parsed != null) {
                    if (parsed.equals(topic)) {
                        entries.add(new IndexEntry(topic, summary));
                        found = true;
                    } else {
                        entries.add(new IndexEntry(parsed, null)); // 保留原摘要
                    }
                }
            }
        }
        if (!found) {
            entries.add(new IndexEntry(topic, summary));
        }
        // 写回
        StringBuilder sb = new StringBuilder("# Knowledge Base\n\n");
        sb.append("<!-- 本文件由 LocalKnowledgeService 自动维护，请勿手动编辑 -->\n\n");
        sb.append("> 知识库内容在启动时自动注入系统提示。");
        sb.append("如需新增条目，请使用 `knowledge_write` 工具。\n\n");
        for (IndexEntry e : entries) {
            // 读取实际摘要（如果列表里没存）
            String actualSummary = e.summary != null ? e.summary : readSummary(dir.resolve(e.topic + ".md"));
            sb.append("- [").append(e.topic).append("](").append(e.topic).append(".md)")
                    .append(" — ").append(actualSummary).append("\n");
        }
        Files.writeString(indexFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 从 md 文件的 YAML 头读取 summary */
    private String readSummary(Path file) {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean inFrontMatter = false;
            for (String line : lines) {
                if (line.trim().equals("---")) {
                    inFrontMatter = !inFrontMatter;
                    continue;
                }
                if (inFrontMatter && line.startsWith("summary:")) {
                    return line.substring("summary:".length()).trim();
                }
                if (!inFrontMatter) {
                    break; // 已过 front matter
                }
            }
        } catch (IOException ignore) {
            // 静默
        }
        return "";
    }

    /** 解析索引行 {@code - [topic](topic.md) — summary}，返回 topic 或 null */
    private String parseIndexLine(String line) {
        line = line.trim();
        if (!line.startsWith("- [")) {
            return null;
        }
        int closeBracket = line.indexOf("](");
        if (closeBracket < 0) {
            return null;
        }
        String topic = line.substring(3, closeBracket); // 跳过 "- ["
        int closeParen = line.indexOf(")", closeBracket);
        if (closeParen < 0) {
            return null;
        }
        String ref = line.substring(closeBracket + 2, closeParen);
        if (!ref.equals(topic + ".md")) {
            return null;
        }
        return topic;
    }

    private String truncate(String content) {
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + "\n\n…[truncated]";
    }

    /**
     * 对单个知识文件做 AND 子串匹配，返回命中候选；任一词在条目名/摘要/正文中都不出现则返回 null。
     * <p>
     * 权重：条目名命中 = 3 &gt; 摘要命中 = 2 &gt; 仅正文命中 = 1。
     * 正文 = 去掉 YAML front matter 后的部分，避免 front matter 里的
     * {@code topic:}/{@code summary:} 行被重复计入正文命中。
     */
    private SearchCandidate matchFile(Path file, List<String> terms) throws IOException {
        String fileName = file.getFileName().toString();
        String topic = fileName.substring(0, fileName.length() - ".md".length());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        // 拆 front matter：首行为 --- 时，到下一个 --- 为止
        String summary = "";
        int bodyStart = 0;
        if (!lines.isEmpty() && lines.get(0).trim().equals("---")) {
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("---")) {
                    bodyStart = i + 1;
                    break;
                }
                if (lines.get(i).startsWith("summary:")) {
                    summary = lines.get(i).substring("summary:".length()).trim();
                }
            }
        }
        List<String> bodyLines = lines.subList(Math.min(bodyStart, lines.size()), lines.size());

        String topicLower = topic.toLowerCase(Locale.ROOT);
        String summaryLower = summary.toLowerCase(Locale.ROOT);
        boolean topicHit = false;
        boolean summaryHit = false;
        boolean[] termMatched = new boolean[terms.size()];
        for (int t = 0; t < terms.size(); t++) {
            if (topicLower.contains(terms.get(t))) {
                termMatched[t] = true;
                topicHit = true;
            }
            if (summaryLower.contains(terms.get(t))) {
                termMatched[t] = true;
                summaryHit = true;
            }
        }

        int anchor = -1;
        for (int i = 0; i < bodyLines.size(); i++) {
            String lower = bodyLines.get(i).toLowerCase(Locale.ROOT);
            boolean anyHit = false;
            for (int t = 0; t < terms.size(); t++) {
                if (lower.contains(terms.get(t))) {
                    termMatched[t] = true;
                    anyHit = true;
                }
            }
            if (anyHit && anchor < 0) {
                anchor = i;
            }
        }

        for (boolean matched : termMatched) {
            if (!matched) {
                return null; // AND 语义：任一词不命中即整文件不命中
            }
        }

        int weight = topicHit ? 3 : (summaryHit ? 2 : 1);
        String snippet = anchor >= 0 ? buildSnippet(bodyLines, anchor) : summary;
        return new SearchCandidate(topic, summary, snippet, weight);
    }

    /** 命中片段：首个正文命中行及其上下各 1 行（跳过空行），约 200 字符截断 */
    private String buildSnippet(List<String> bodyLines, int anchor) {
        int from = Math.max(0, anchor - 1);
        int to = Math.min(bodyLines.size(), anchor + 2); // exclusive
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            String line = bodyLines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ⏎ ");
            }
            sb.append(line);
        }
        String s = sb.toString();
        return s.length() <= SNIPPET_MAX_CHARS ? s : s.substring(0, SNIPPET_MAX_CHARS) + "…";
    }

    /** 搜索候选（内部辅助，带排序权重） */
    private record SearchCandidate(String topic, String summary, String snippet, int weight) {
    }

    /** 索引条目（内部辅助） */
    private record IndexEntry(String topic, String summary) {
    }
}