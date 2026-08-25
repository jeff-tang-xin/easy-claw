package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public SessionHistoryService(SessionRepository sessionRepository, WorkspaceManager workspaceManager) {
        this.sessionRepository = sessionRepository;
        this.workspaceManager = workspaceManager;
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
        try {
            Path stateDir = workspace.getPath().resolve(".easyClaw/agent/state")
                    .resolve(workspace.getUserId() == null ? AppConstants.DEFAULT_USER_ID : workspace.getUserId())
                    .resolve(sessionId);
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
            log.info("已删除会话: {}（工作区 {}）", sessionId, workspace.getWorkspaceId());
        } catch (IOException e) {
            log.warn("删除会话状态文件失败: {}", e.getMessage());
        }
    }
}
