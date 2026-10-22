package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.SessionTranscriptStore;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 会话历史服务
 * <p>
 * 负责读取 Workspace 的历史会话列表与删除会话。
 * 消息内容（时序盒子）直接复用 Agent 状态存储的 agent_state.json，
 * 由 {@code AgentStateBoxReader} 解析，这里不再重复解析。
 */
@Service
public class SessionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryService.class);

    /** 会话标题长度上限：够长以容纳一句摘要，又不至于撑破列表布局与数据库列 */
    private static final int MAX_TITLE_LENGTH = 100;

    private final SessionRepository sessionRepository;
    private final WorkspaceManager workspaceManager;
    private final WorkspaceFileLayout workspaceFileLayout;
    private final WorkspaceRepository workspaceRepository;
    /** 延迟解析 AgentService：仅保留期清理需要（防删运行中会话），避免启动期强依赖 */
    private final ObjectProvider<AgentService> agentServiceProvider;
    /** 会话保留天数：最后活跃（transcript mtime，缺省回退 createdAt）超过该值即清理；<=0 禁用 */
    private final int retentionDays;

    public SessionHistoryService(SessionRepository sessionRepository, WorkspaceManager workspaceManager,
                                 WorkspaceFileLayout workspaceFileLayout, WorkspaceRepository workspaceRepository,
                                 ObjectProvider<AgentService> agentServiceProvider,
                                 @Value("${easyclaw.session.retention-days:180}") int retentionDays) {
        this.sessionRepository = sessionRepository;
        this.workspaceManager = workspaceManager;
        this.workspaceFileLayout = workspaceFileLayout;
        this.workspaceRepository = workspaceRepository;
        this.agentServiceProvider = agentServiceProvider;
        this.retentionDays = retentionDays;
    }

    /**
     * 列出 Workspace 的历史会话（按创建时间倒序）
     */
    public List<SessionEntity> listSessions(String workspaceId) {
        return sessionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /**
     * 重命名会话。
     * <p>
     * 标题会去除首尾空白并截断到 {@value #MAX_TITLE_LENGTH} 字符；空白标题视为非法输入而拒绝，
     * 避免会话在列表里变成不可点选的空条目。
     * 同时同步 {@code WorkspaceManager} 的会话缓存，否则缓存里的旧标题会在后续读取时把改名覆盖回去。
     *
     * @return 重命名后的实体
     * @throws WorkspaceExceptions.SessionNotFoundException 会话不存在
     * @throws IllegalArgumentException                     标题为空或仅含空白
     */
    public SessionEntity renameSession(String workspaceId, String sessionId, String rawTitle) {
        String title = normalizeTitle(rawTitle);
        SessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new WorkspaceExceptions.SessionNotFoundException("会话未找到: " + sessionId));
        // 校验归属，防止越过工作区边界改动他人会话
        if (!entity.getWorkspaceId().equals(workspaceId)) {
            throw new WorkspaceExceptions.SessionNotFoundException(
                    "会话 " + sessionId + " 不属于工作区 " + workspaceId);
        }
        entity.setTitle(title);
        SessionEntity saved = sessionRepository.save(entity);
        workspaceManager.updateSessionTitle(sessionId, title);
        log.info("会话已重命名: {} → 「{}」（工作区 {}）", sessionId, title, workspaceId);
        return saved;
    }

    private String normalizeTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        String trimmed = rawTitle.trim();
        return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
    }

    /**
     * 删除会话：删除 SQLite 记录与 Workspace 内的 Agent 状态（对话数据在 workspace 目录下）
     */
    public void deleteSession(WorkspaceContext workspace, String sessionId) {
        sessionRepository.deleteById(sessionId);
        purgeSessionStateDir(workspaceFileLayout.sessionStateDir(
                workspace.getPath(), workspace.getUserId(), sessionId));
        log.info("已删除会话: {}（工作区 {}）", sessionId, workspace.getWorkspaceId());
    }

    /**
     * 会话保留期清理（每天凌晨 4 点）：删除最后活跃时间超过 {@code easyclaw.session.retention-days}
     * （默认 180 天，<=0 禁用）的会话——SQLite 记录 + 会话状态目录 + 转录缓存，与手动删除同路径。
     * <p>
     * 「最后活跃」取 transcript.jsonl 的文件 mtime（每次对话追加都会刷新），比
     * {@code lastAccessedAt} 可靠——后者仅在创建会话时写入一次，使用中不更新，
     * 若按它判定会把长期使用的老会话误杀。无转录文件的空会话回退 createdAt。
     * <p>
     * 运行中或有挂起确认的会话本轮跳过（{@link AgentService#releaseSessionIfIdle} 返回 false），
     * 等下一轮清扫；工作区已删除的孤儿会话只清 DB 记录。
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpiredSessions() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<SessionEntity> all = sessionRepository.findAll();
        int purged = 0;
        int skipped = 0;
        for (SessionEntity session : all) {
            Instant lastActive = resolveLastActiveAt(session);
            if (lastActive == null || !lastActive.isBefore(cutoff)) {
                continue;
            }
            AgentService agentService = agentServiceProvider.getIfAvailable();
            if (agentService != null && !agentService.releaseSessionIfIdle(session.getId())) {
                skipped++;
                continue;
            }
            WorkspaceEntity ws = workspaceRepository.findById(session.getWorkspaceId()).orElse(null);
            if (ws == null) {
                // 工作区已不存在：状态目录无从定位，只清 DB 记录
                sessionRepository.deleteById(session.getId());
            } else {
                sessionRepository.deleteById(session.getId());
                purgeSessionStateDir(workspaceFileLayout.sessionStateDir(
                        Path.of(ws.getPath()), ws.getUserId(), session.getId()));
            }
            purged++;
        }
        if (purged > 0 || skipped > 0) {
            log.info("会话保留期清理（{} 天未活跃）: 删除 {} 个, 跳过运行中 {} 个", retentionDays, purged, skipped);
        }
    }

    /** 会话最后活跃时间：transcript.jsonl 的 mtime 优先，无转录回退 createdAt。 */
    private Instant resolveLastActiveAt(SessionEntity session) {
        WorkspaceEntity ws = workspaceRepository.findById(session.getWorkspaceId()).orElse(null);
        if (ws != null) {
            Path stateDir = workspaceFileLayout.sessionStateDir(
                    Path.of(ws.getPath()), ws.getUserId(), session.getId());
            Path transcript = SessionTranscriptStore.transcriptFile(stateDir);
            try {
                if (transcript != null && Files.exists(transcript)) {
                    return Files.getLastModifiedTime(transcript).toInstant();
                }
            } catch (IOException e) {
                log.warn("读取转录修改时间失败，回退 createdAt: {}, {}", transcript, e.getMessage());
            }
        }
        return session.getCreatedAt();
    }

    /**
     * 删除会话状态目录并驱逐转录缓存。供手动删除与保留期清理共用；
     * 调用方需保证会话已无活跃回合。
     */
    private void purgeSessionStateDir(Path stateDir) {
        try {
            if (Files.isDirectory(stateDir)) {
                try (var walk = Files.walk(stateDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 忽略单个文件删除失败
                        }
                    });
                }
            }
            // 必须同步清理转录计数缓存：否则同名 sessionId 复用时会继承已删除会话的
            // seq 计数，新转录的序号从一个虚高值开始（历史渲染顺序错乱）
            SessionTranscriptStore.evict(stateDir);
        } catch (IOException e) {
            log.warn("删除会话状态文件失败: {}", e.getMessage());
        }
    }
}
