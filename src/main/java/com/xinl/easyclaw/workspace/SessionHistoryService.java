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

    private final SessionRepository sessionRepository;

    public SessionHistoryService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 列出 Workspace 的历史会话（按创建时间倒序）
     */
    public List<SessionEntity> listSessions(String workspaceId) {
        return sessionRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
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
