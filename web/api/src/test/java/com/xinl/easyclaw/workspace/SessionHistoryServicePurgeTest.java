package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.workspace.entity.SessionEntity;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话保留期清理（{@code purgeExpiredSessions}）行为验证：
 * 判据是 transcript.jsonl 的 mtime（非 lastAccessedAt——它只在创建时写入一次），
 * 运行中会话跳过，孤儿会话只清 DB，retentionDays<=0 禁用。
 */
class SessionHistoryServicePurgeTest {

    @TempDir
    Path temp;

    private SessionRepository sessionRepo;
    private WorkspaceRepository workspaceRepo;
    private WorkspaceManager workspaceManager;
    private ObjectProvider<AgentService> agentProvider;
    private AgentService agentService;
    private SessionHistoryService service;
    private Path workspacePath;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionRepo = mock(SessionRepository.class);
        workspaceRepo = mock(WorkspaceRepository.class);
        workspaceManager = mock(WorkspaceManager.class);
        agentProvider = mock(ObjectProvider.class);
        agentService = mock(AgentService.class);
        when(agentProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.releaseSessionIfIdle(anyString())).thenReturn(true);
        service = new SessionHistoryService(
                sessionRepo, workspaceManager, new WorkspaceFileLayout(),
                workspaceRepo, agentProvider, 180);
        workspacePath = temp.resolve("ws");
    }

    private WorkspaceEntity workspace(String id) {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(id);
        ws.setUserId("u1");
        ws.setPath(workspacePath.toString());
        return ws;
    }

    private SessionEntity session(String id, String workspaceId, Instant createdAt) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setWorkspaceId(workspaceId);
        s.setCreatedAt(createdAt);
        return s;
    }

    /** 在会话状态目录下写一个 transcript.jsonl，mtime 回拨到指定时刻 */
    private Path transcript(String sessionId, Instant mtime) throws IOException {
        Path dir = workspacePath.resolve(".easyClaw/agent/state/u1").resolve(sessionId);
        Files.createDirectories(dir);
        Path file = dir.resolve("transcript.jsonl");
        Files.writeString(file, "{}\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(mtime));
        return dir;
    }

    @Test
    void purgesExpiredSessionWithWorkspace() throws IOException {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(200));
        Path stateDir = transcript("s-old", old);
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-old", "w1", old)));
        when(workspaceRepo.findById("w1")).thenReturn(Optional.of(workspace("w1")));

        service.purgeExpiredSessions();

        verify(sessionRepo).deleteById("s-old");
        assertFalse(Files.exists(stateDir), "超期会话状态目录应被删除");
    }

    @Test
    void keepsFreshSession() throws IOException {
        Instant fresh = Instant.now().minus(java.time.Duration.ofDays(1));
        Path stateDir = transcript("s-new", fresh);
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-new", "w1", old())));
        when(workspaceRepo.findById("w1")).thenReturn(Optional.of(workspace("w1")));

        service.purgeExpiredSessions();

        verify(sessionRepo, never()).deleteById(anyString());
        assertTrue(Files.exists(stateDir), "活跃会话不应被清理");
    }

    @Test
    void skipsRunningSession() throws IOException {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(200));
        Path stateDir = transcript("s-run", old);
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-run", "w1", old)));
        when(workspaceRepo.findById("w1")).thenReturn(Optional.of(workspace("w1")));
        when(agentService.releaseSessionIfIdle("s-run")).thenReturn(false);

        service.purgeExpiredSessions();

        verify(sessionRepo, never()).deleteById(anyString());
        assertTrue(Files.exists(stateDir), "运行中会话应跳过等下轮");
    }

    @Test
    void purgesOrphanSessionRecordOnly() {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(200));
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-orphan", "w-gone", old)));
        when(workspaceRepo.findById("w-gone")).thenReturn(Optional.empty());

        service.purgeExpiredSessions();

        verify(sessionRepo).deleteById("s-orphan");
    }

    @Test
    void fallsBackToCreatedAtWhenNoTranscript() {
        Instant old = Instant.now().minus(java.time.Duration.ofDays(200));
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-empty", "w1", old)));
        when(workspaceRepo.findById("w1")).thenReturn(Optional.of(workspace("w1")));

        service.purgeExpiredSessions();

        verify(sessionRepo).deleteById("s-empty");
    }

    @Test
    void disabledWhenRetentionDaysZero() {
        SessionHistoryService disabled = new SessionHistoryService(
                sessionRepo, workspaceManager, new WorkspaceFileLayout(),
                workspaceRepo, agentProvider, 0);
        when(sessionRepo.findAll()).thenReturn(List.of(session("s-any", "w1", old())));

        disabled.purgeExpiredSessions();

        verify(sessionRepo, never()).deleteById(anyString());
        Mockito.verifyNoInteractions(workspaceRepo);
    }

    private Instant old() {
        return Instant.now().minus(java.time.Duration.ofDays(300));
    }
}
