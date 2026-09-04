package com.xinl.easyclaw.blackboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 共享记录本（blackboard）存储：工作区内的 append-only JSONL。
 * <p>
 * 存在的意义：team 模式下并行子 Agent 互不可见（各自独立会话、独立上下文），
 * 谁也读不到同伴的结论。本存储给它们一块公共黑板 —— 只能追加、谁都能读，
 * 从而让「A 发现的风险」能影响「B 的做法」。
 * <p>
 * <b>为什么落文件而不是建表：</b>底层是 SQLite（单写者、未配 busy_timeout），
 * 并行子 Agent 同时写极易 SQLITE_BUSY；而 {@code CREATE + APPEND} 写单行是本地文件的
 * 天然强项，坏行还能逐行跳过，可用性明显更高。
 * <p>
 * <b>并发模型：</b>同一 JVM 内按 key 分段 {@link ReentrantLock}，锁内「取序号 → 写盘」，
 * 保证 seq 连续且与文件内容一致。刻意不用 {@code FileLock}：本系统只有一个进程写，
 * 跨进程锁在 Windows 上还会带来释放不及时的麻烦。
 * <p>
 * 文件位置：{@code <workspace>/.easyClaw/agent/blackboard/<safeKey>.jsonl}。
 * 该目录在 {@code application.yml} 的 forbidden-paths 内，AI 无法用文件工具直读或篡改，
 * 只能经由 blackboard 工具访问 —— 这正是想要的：不给删改他人条目的口子。
 */
@Component
public class BlackboardStore {

    private static final Logger log = LoggerFactory.getLogger(BlackboardStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条正文上限（字符）：超出截断，防止一条把整块黑板挤满 */
    private static final int MAX_CONTENT_CHARS = 4_000;
    /**
     * 单个记录本文件上限：超出拒绝写入，避免无限增长。
     * 以文件<b>字节数</b>衡量（UTF-8 下字节数 ≥ 字符数，故对「20 万字符」是保守判定），
     * 这样每次追加只需一次 {@code Files.size}，不必把全文读出来数字符。
     */
    private static final long MAX_FILE_BYTES = 200_000L;
    /** 读取条数上限 */
    public static final int MAX_READ_LIMIT = 100;
    /** 读取默认条数 */
    public static final int DEFAULT_READ_LIMIT = 30;

    /** key → 写锁（同 JVM 内串行化同一记录本的写入） */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** key → 序号发号器；首次按现有行数初始化，之后锁内自增 */
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    /**
     * 追加一条记录，返回给 LLM 看的简短结果说明。
     *
     * @param workspace 当前工作区（决定文件落在哪）
     * @param key       记录本隔离键（通常是父会话 id）
     * @param author    登记者名（由调用方从运行时上下文解析，不接受 LLM 传入）
     * @param type      条目类型（调用方已做白名单校验）
     * @param content   正文（非空，由调用方校验）
     * @return 形如 {@code ✅ #12 已登记（risk, by main）} 的说明；失败时为可读的失败原因
     */
    public String append(WorkspaceContext workspace, String key, String author, String type, String content) {
        Path file = blackboardFile(workspace, key);
        String body = truncate(content);
        ReentrantLock lock = locks.computeIfAbsent(fileKey(file), k -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            long existingBytes = Files.exists(file) ? Files.size(file) : 0L;
            if (existingBytes > MAX_FILE_BYTES) {
                return "❌ 记录本已达容量上限（约 " + MAX_FILE_BYTES + " 字节），本条未登记。请改为在回复中直接汇总结论。";
            }
            long seq = sequencer(file).incrementAndGet();
            BlackboardEntry entry = new BlackboardEntry(
                    seq,
                    OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    author,
                    type,
                    body);
            Files.writeString(file,
                    MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "✅ #" + seq + " 已登记（" + type + ", by " + author + "）"
                    + (body.length() < content.length() ? "；正文过长已截断" : "");
        } catch (IOException e) {
            // 不吞异常：记录本写失败会让协作静默失效，必须让 LLM 知道这条没写进去
            log.warn("写入记录本失败: {}, {}", file, e.getMessage());
            return "❌ 记录本写入失败：" + e.getMessage() + "（本条未登记）";
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取最近 {@code limit} 条记录（按 seq 升序返回，便于按时间顺序阅读）。
     * <p>
     * 逐行容错：坏行（如进程被杀导致写一半）跳过并告警，保住其余内容。
     *
     * @param limit 条数；{@code <= 0} 用默认值，超过 {@link #MAX_READ_LIMIT} 取上限
     */
    public List<BlackboardEntry> read(WorkspaceContext workspace, String key, int limit) {
        Path file = blackboardFile(workspace, key);
        if (!Files.exists(file)) {
            return List.of();
        }
        int want = limit <= 0 ? DEFAULT_READ_LIMIT : Math.min(limit, MAX_READ_LIMIT);
        List<BlackboardEntry> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    all.add(MAPPER.readValue(line, BlackboardEntry.class));
                } catch (Exception bad) {
                    log.warn("记录本存在坏行（已跳过）: {}", file);
                }
            }
        } catch (IOException e) {
            log.warn("读取记录本失败: {}, {}", file, e.getMessage());
            return List.of();
        }
        all.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        if (all.size() <= want) {
            return all;
        }
        // 取最近 want 条，仍按 seq 升序
        return List.copyOf(all.subList(all.size() - want, all.size()));
    }

    /** 记录本文件路径；key 经 safe 化后作为文件名，防止越出 blackboard 目录 */
    private Path blackboardFile(WorkspaceContext workspace, String key) {
        return blackboardDir(workspace)
                .resolve(safeKey(key) + ".jsonl")
                .normalize();
    }

    /** 记录本根目录：{@code <workspace>/.easyClaw/agent/blackboard} */
    private Path blackboardDir(WorkspaceContext workspace) {
        return workspace.getPath()
                .resolve(".easyClaw").resolve("agent").resolve("blackboard")
                .normalize();
    }

    /**
     * 列出该工作区下所有记录本的 key（即 jsonl 文件名去掉后缀），按最后修改时间倒序。
     * <p>
     * 供管理页面浏览用：{@link #read} 只能按已知 key 取单个记录本，而页面需要先知道
     * 「这个工作区里有哪些记录本」。返回的是 safeKey 化后的名字（磁盘真实文件名），
     * 可直接回传给 {@link #read} —— safeKey 是幂等的，二次处理不会变形。
     *
     * @return key 列表；目录不存在或读取失败时返回空列表（管理页面不应因此报错）
     */
    public List<BlackboardBook> listBooks(WorkspaceContext workspace) {
        Path dir = blackboardDir(workspace);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<BlackboardBook> books = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).toList()) {
                String fileName = p.getFileName().toString();
                String key = fileName.substring(0, fileName.length() - ".jsonl".length());
                long modified = 0L;
                long entries = 0L;
                try {
                    modified = Files.getLastModifiedTime(p).toMillis();
                    entries = countLines(p);
                } catch (IOException ignore) {
                    // 单个文件读不到不应让整个列表失败，保留 key 让用户至少看到它存在
                }
                books.add(new BlackboardBook(key, entries, modified));
            }
        } catch (IOException e) {
            log.warn("列出记录本目录失败: {}, {}", dir, e.getMessage());
            return List.of();
        }
        books.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return List.copyOf(books);
    }

    /**
     * 一个记录本的概要（管理页面列表用）。
     *
     * @param key          记录本键（磁盘文件名去后缀，通常是会话 id）
     * @param entries      有效条目数
     * @param lastModified 最后修改时间（epoch millis）
     */
    public record BlackboardBook(String key, long entries, long lastModified) {
    }

    /**
     * key → 安全文件名：只保留字母数字与 {@code - _}，其余一律换成 {@code _}。
     * 这样 {@code ../}、盘符、非法字符都不可能残留。
     */
    static String safeKey(String key) {
        if (key == null || key.isBlank()) {
            return "default";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (char c : key.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        String s = sb.toString();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private String fileKey(Path file) {
        return file.toAbsolutePath().toString();
    }

    /**
     * 取该文件的发号器：首次以现有有效行数初始化（服务重启后接着往下发号，
     * 不会把已有条目的 seq 重复一遍）。仅在写锁内调用。
     */
    private AtomicLong sequencer(Path file) {
        return sequences.computeIfAbsent(fileKey(file), k -> new AtomicLong(countLines(file)));
    }

    private long countLines(Path file) {
        if (!Files.exists(file)) {
            return 0L;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> l != null && !l.isBlank())
                    .count();
        } catch (IOException e) {
            log.warn("统计记录本行数失败，序号从 0 起: {}, {}", file, e.getMessage());
            return 0L;
        }
    }

    private String truncate(String content) {
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + "…[truncated]";
    }
}
