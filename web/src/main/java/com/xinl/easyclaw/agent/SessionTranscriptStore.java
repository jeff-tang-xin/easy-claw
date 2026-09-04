package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话转录存储（append-only transcript.jsonl）。
 * <p>
 * 解决的问题：历史消息此前唯一来源是 agent_state.json（模型工作上下文），
 * 上下文压缩（compaction）一旦触发，旧消息被摘要替换 → UI 上「几轮对话消失」。
 * <p>
 * 本存储与模型上下文彻底解耦：把推送给 UI 的完整事件流按回合聚合落盘，
 * 只增不删，历史接口优先读取转录，模型侧怎么压缩都不影响展示历史。
 * <p>
 * 文件位置：与 agent_state.json 同目录（.easyClaw/agent/state/{userId}/{sessionId}/transcript.jsonl），
 * 会话删除时随目录一并清理。
 */
public final class SessionTranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(SessionTranscriptStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
    public static final String FILE_NAME = "transcript.jsonl";

    /** 单次 {@link #read} 最多打印多少条坏行明细，其余只计入结尾汇总（避免刷爆日志）。 */
    private static final int BAD_LINE_WARN_LIMIT = 5;

    /**
     * 按会话分段的写锁：此前所有方法都是 {@code static synchronized}，用的是
     * 全局类锁 —— 任一会话写盘会阻塞其他所有会话的转录读写。
     */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * 条目数缓存：{@code countEntries} 原实现每次调用都把整个 jsonl 全文读一遍
     * （长会话可达 MB 级，且每轮至少调用两次），仅为拿到一个递增 seq。
     * 这里首次按需读一次，之后随 append 增量维护。
     */
    private static final Map<Path, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private SessionTranscriptStore() {
    }

    private static Object lockFor(Path sessionDir) {
        return LOCKS.computeIfAbsent(sessionDir.toAbsolutePath().normalize(), k -> new Object());
    }

    public static Path transcriptFile(Path sessionDir) {
        return sessionDir == null ? null : sessionDir.resolve(FILE_NAME);
    }

    /**
     * 追加一条消息（写失败仅告警，绝不影响对话主流程）。
     */
    public static void append(Path sessionDir, BoxMessage msg) {
        if (sessionDir == null || msg == null) {
            return;
        }
        synchronized (lockFor(sessionDir)) {
            try {
                Files.createDirectories(sessionDir);
                try (BufferedWriter w = Files.newBufferedWriter(
                        sessionDir.resolve(FILE_NAME),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(MAPPER.writeValueAsString(msg));
                    w.newLine();
                }
                // 写成功才递增计数，保证缓存与文件一致
                AtomicLong c = COUNTS.get(key(sessionDir));
                if (c != null) {
                    c.incrementAndGet();
                }
            } catch (Exception e) {
                log.warn("写入会话转录失败（忽略）: {}, {}", sessionDir, e.getMessage());
            }
        }
    }

    /**
     * 读取全部转录消息；文件不存在/解析失败返回空列表（逐行容错，坏行跳过）。
     * <p>
     * 坏行会限流告警：每次读取最多打印 {@link #BAD_LINE_WARN_LIMIT} 条明细，
     * 结尾再补一条总数汇总。此前这里是静默 {@code catch}，一旦落盘格式改错，
     * 现象只是「历史消息悄悄变少」而无任何日志，几乎无法定位。
     */
    public static List<BoxMessage> read(Path sessionDir) {
        List<BoxMessage> out = new ArrayList<>();
        Path file = transcriptFile(sessionDir);
        if (file == null || !Files.exists(file)) {
            return out;
        }
        synchronized (lockFor(sessionDir)) {
            int badLines = 0;
            int lineNo = 0;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNo++;
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    try {
                        out.add(MAPPER.readValue(line, BoxMessage.class));
                    } catch (Exception e) {
                        // 坏行（如写一半崩溃）跳过，保住其余历史；但必须留下痕迹
                        badLines++;
                        if (badLines <= BAD_LINE_WARN_LIMIT) {
                            log.warn("会话转录第 {} 行解析失败（已跳过）: {}, {}", lineNo, file, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取会话转录失败: {}, {}", file, e.getMessage());
            }
            if (badLines > 0) {
                log.warn("会话转录共跳过 {} 个坏行（已读出 {} 条）: {}", badLines, out.size(), file);
            }
        }
        return out;
    }

    /**
     * 当前转录条数（用于为新消息分配递增 seq）。
     * <p>首次调用扫描文件一次，随后由 {@link #append} 增量维护，避免每轮全文重读。</p>
     */
    public static long countEntries(Path sessionDir) {
        if (sessionDir == null) {
            return 0;
        }
        return COUNTS.computeIfAbsent(key(sessionDir),
                k -> new AtomicLong(scanCount(sessionDir))).get();
    }

    /** 全文扫描行数（仅在计数缓存未建立时调用一次） */
    private static long scanCount(Path sessionDir) {
        Path file = transcriptFile(sessionDir);
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        synchronized (lockFor(sessionDir)) {
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return lines.filter(l -> l != null && !l.isBlank()).count();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static Path key(Path sessionDir) {
        return sessionDir.toAbsolutePath().normalize();
    }

    /**
     * 释放会话的锁与计数缓存条目（会话删除/驱逐时调用，避免长期运行累积）。
     */
    public static void evict(Path sessionDir) {
        if (sessionDir == null) {
            return;
        }
        Path k = key(sessionDir);
        LOCKS.remove(k);
        COUNTS.remove(k);
    }

    /**
     * 种子化：转录文件不存在时，把 agent_state.json 当前全部历史一次性快照进转录。
     * 旧会话升级后首次发消息时调用——赶在未来的上下文压缩之前把历史固化下来。
     */
    public static void seedIfAbsent(Path sessionDir, Path agentStateJson) {
        try {
            Path file = transcriptFile(sessionDir);
            if (file == null || Files.exists(file)) {
                return;
            }
            List<BoxMessage> seed = AgentStateBoxReader.read(agentStateJson);
            if (seed.isEmpty()) {
                return;
            }
            for (BoxMessage bm : seed) {
                append(sessionDir, bm);
            }
            log.info("会话转录已种子化 {} 条历史: {}", seed.size(), file);
        } catch (Exception e) {
            log.warn("会话转录种子化失败（忽略）: {}", e.getMessage());
        }
    }
}
