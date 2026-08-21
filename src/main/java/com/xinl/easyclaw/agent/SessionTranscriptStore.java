package com.xinl.easyclaw.agent;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String FILE_NAME = "transcript.jsonl";

    private SessionTranscriptStore() {
    }

    public static Path transcriptFile(Path sessionDir) {
        return sessionDir == null ? null : sessionDir.resolve(FILE_NAME);
    }

    /**
     * 追加一条消息（写失败仅告警，绝不影响对话主流程）。
     */
    public static synchronized void append(Path sessionDir, BoxMessage msg) {
        if (sessionDir == null || msg == null) {
            return;
        }
        try {
            Files.createDirectories(sessionDir);
            try (BufferedWriter w = Files.newBufferedWriter(
                    sessionDir.resolve(FILE_NAME),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(MAPPER.writeValueAsString(msg));
                w.newLine();
            }
        } catch (Exception e) {
            log.warn("写入会话转录失败（忽略）: {}, {}", sessionDir, e.getMessage());
        }
    }

    /**
     * 读取全部转录消息；文件不存在/解析失败返回空列表（逐行容错，坏行跳过）。
     */
    public static synchronized List<BoxMessage> read(Path sessionDir) {
        List<BoxMessage> out = new ArrayList<>();
        Path file = transcriptFile(sessionDir);
        if (file == null || !Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readValue(line, BoxMessage.class));
                } catch (Exception ignore) {
                    // 坏行（如写一半崩溃）跳过，保住其余历史
                }
            }
        } catch (Exception e) {
            log.warn("读取会话转录失败: {}, {}", file, e.getMessage());
        }
        return out;
    }

    /**
     * 当前转录条数（用于为新消息分配递增 seq）。
     */
    public static synchronized long countEntries(Path sessionDir) {
        Path file = transcriptFile(sessionDir);
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(l -> l != null && !l.isBlank())
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 种子化：转录文件不存在时，把 agent_state.json 当前全部历史一次性快照进转录。
     * 旧会话升级后首次发消息时调用——赶在未来的上下文压缩之前把历史固化下来。
     */
    public static synchronized void seedIfAbsent(Path sessionDir, Path agentStateJson) {
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
