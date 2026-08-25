package com.xinl.easyclaw.api;

import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link WorkspaceAccessGuard} 单测（code-review Finding 6）。
 * <p>重点覆盖三类越权入口：畸形 ID（路径穿越）、跨用户工作区、跨工作区会话。
 */
class WorkspaceAccessGuardTest {

    private WorkspaceManager workspaceManager;
    private SessionRepository sessionRepository;
    private WorkspaceAccessGuard guard;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(WorkspaceManager.class);
        sessionRepository = mock(SessionRepository.class);
        guard = new WorkspaceAccessGuard(workspaceManager, sessionRepository);
    }

    private WorkspaceContext ownedWorkspace() {
        WorkspaceContext ctx = mock(WorkspaceContext.class);
        when(ctx.getUserId()).thenReturn(AppConstants.DEFAULT_USER_ID);
        return ctx;
    }

    @Test
    @DisplayName("空 workspaceId 报 400")
    void blankIdRejected() {
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkWorkspace(""));
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkWorkspace(null));
    }

    @Test
    @DisplayName("路径穿越 ID 被字符白名单拦住，不落到 WorkspaceManager")
    void pathTraversalRejected() {
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkWorkspace("../../etc"));
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkIdFormat("sessionId", "a/b"));
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkIdFormat("sessionId", "a\\b"));
        verifyNoInteractions(workspaceManager);
    }

    @Test
    @DisplayName("超长 ID（>64）被拒")
    void oversizedIdRejected() {
        String tooLong = "a".repeat(65);
        assertThrows(ApiExceptions.BadRequestException.class, () -> guard.checkIdFormat("sessionId", tooLong));
        assertDoesNotThrow(() -> guard.checkIdFormat("sessionId", "a".repeat(64)));
    }

    @Test
    @DisplayName("常规 ID 形态（session-时间戳 / UUID）放行")
    void normalIdAccepted() {
        assertDoesNotThrow(() -> guard.checkIdFormat("sessionId", "session-1787619449666"));
        assertDoesNotThrow(() -> guard.checkIdFormat("sessionId", "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071"));
        assertDoesNotThrow(() -> guard.checkIdFormat("workspaceId", "ws_Default-01"));
    }

    @Test
    @DisplayName("工作区不存在报 404")
    void missingWorkspaceIs404() {
        when(workspaceManager.getWorkspace("ws1")).thenReturn(null);
        assertThrows(ApiExceptions.NotFoundException.class, () -> guard.checkWorkspace("ws1"));
    }

    @Test
    @DisplayName("工作区属于他人报 403")
    void foreignWorkspaceIs403() {
        WorkspaceContext other = mock(WorkspaceContext.class);
        when(other.getUserId()).thenReturn("someone-else");
        when(workspaceManager.getWorkspace("ws1")).thenReturn(other);
        assertThrows(ApiExceptions.ForbiddenException.class, () -> guard.checkWorkspace("ws1"));
    }

    @Test
    @DisplayName("workspace.userId 为空按默认用户处理（兼容历史数据）")
    void nullOwnerTreatedAsDefaultUser() {
        WorkspaceContext legacy = mock(WorkspaceContext.class);
        when(legacy.getUserId()).thenReturn(null);
        when(workspaceManager.getWorkspace("ws1")).thenReturn(legacy);
        assertSame(legacy, guard.checkWorkspace("ws1"));
    }

    @Test
    @DisplayName("会话挂在本工作区下 → 放行")
    void ownSessionAccepted() {
        WorkspaceContext ws = ownedWorkspace();
        when(workspaceManager.getWorkspace("ws1")).thenReturn(ws);
        when(sessionRepository.existsByIdAndWorkspaceId("s1", "ws1")).thenReturn(true);
        assertSame(ws, guard.checkSession("ws1", "s1", true));
        verify(sessionRepository, never()).existsById(anyString());
    }

    @Test
    @DisplayName("会话存在但属于别的工作区 → 403（核心越权场景）")
    void crossWorkspaceSessionIs403() {
        // 先求值再 stub：若写成 thenReturn(ownedWorkspace())，内层 when() 会在外层 stubbing
        // 未闭合时触发，Mockito 报 UnfinishedStubbingException
        WorkspaceContext ws = ownedWorkspace();
        when(workspaceManager.getWorkspace("ws1")).thenReturn(ws);
        when(sessionRepository.existsByIdAndWorkspaceId("s-other", "ws1")).thenReturn(false);
        when(sessionRepository.existsById("s-other")).thenReturn(true);
        assertThrows(ApiExceptions.ForbiddenException.class,
                () -> guard.checkSession("ws1", "s-other", false));
    }

    @Test
    @DisplayName("会话未落库：requireExisting=false 放行（新会话首条消息），true 报 404")
    void unsavedSessionDependsOnRequireExisting() {
        WorkspaceContext ws = ownedWorkspace();
        when(workspaceManager.getWorkspace("ws1")).thenReturn(ws);
        when(sessionRepository.existsByIdAndWorkspaceId("s-new", "ws1")).thenReturn(false);
        when(sessionRepository.existsById("s-new")).thenReturn(false);

        assertDoesNotThrow(() -> guard.checkSession("ws1", "s-new", false));
        assertThrows(ApiExceptions.NotFoundException.class,
                () -> guard.checkSession("ws1", "s-new", true));
    }

    @Test
    @DisplayName("checkSession 先校验工作区：工作区越权时不查会话表")
    void workspaceCheckedBeforeSession() {
        WorkspaceContext other = mock(WorkspaceContext.class);
        when(other.getUserId()).thenReturn("someone-else");
        when(workspaceManager.getWorkspace("ws1")).thenReturn(other);
        assertThrows(ApiExceptions.ForbiddenException.class, () -> guard.checkSession("ws1", "s1", false));
        verifyNoInteractions(sessionRepository);
    }

    @Test
    @DisplayName("会话 ID 畸形时不查库")
    void malformedSessionIdShortCircuits() {
        WorkspaceContext ws = ownedWorkspace();
        when(workspaceManager.getWorkspace("ws1")).thenReturn(ws);
        assertThrows(ApiExceptions.BadRequestException.class,
                () -> guard.checkSession("ws1", "../secret", false));
        verifyNoInteractions(sessionRepository);
    }

    @Test
    @DisplayName("单用户模式下当前用户为 DEFAULT_USER_ID")
    void currentUserIsDefault() {
        assertEquals(AppConstants.DEFAULT_USER_ID, guard.currentUserId());
    }
}
