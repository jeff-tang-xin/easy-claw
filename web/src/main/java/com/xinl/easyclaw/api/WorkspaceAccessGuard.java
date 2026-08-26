package com.xinl.easyclaw.api;

import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.repository.SessionRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 入口层归属校验：确保请求中的 {@code workspaceId} / {@code sessionId} 真属于调用方。
 * <p>
 * 背景（code-review Finding 6）：SSE / WS / REST 各端点都直接把请求体里的 ID 透传给
 * {@code AgentService}，未做任何归属校验。这构成完整越权链：
 * 读他人历史 → 给他人工作区植入永久工具白名单 → 借该工作区沙箱执行文件 / Shell 操作。
 * <p>
 * 校验分三层，缺一不可：
 * <ol>
 *   <li><b>字符白名单</b>：拦住 {@code ../}、超长串、控制字符——这些 ID 会被拼进文件系统路径，
 *       仅靠下游沙箱兜底不够（history 端点就直接用 sessionId 拼 state 目录）。</li>
 *   <li><b>工作区归属</b>：workspace 的 userId 必须等于调用方 userId。</li>
 *   <li><b>会话归属</b>：sessionId 必须挂在该 workspaceId 下，否则 A 工作区的请求能停掉 B 的回合。</li>
 * </ol>
 * 当前为单用户模式（userId 固定 {@link AppConstants#DEFAULT_USER_ID}），
 * 但校验逻辑按多用户写好：接入认证后只需把 {@link #currentUserId()} 换成真实主体来源。
 */
@Component
public class WorkspaceAccessGuard {

    /**
     * ID 字符白名单：字母数字加连字符下划线，1~64 位。
     * <p>现有 ID 形如 {@code session-1787619449666} / UUID，均满足；
     * 不放开点号是为了从根上排除 {@code ..} 路径穿越。
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final WorkspaceManager workspaceManager;
    private final SessionRepository sessionRepository;

    public WorkspaceAccessGuard(WorkspaceManager workspaceManager, SessionRepository sessionRepository) {
        this.workspaceManager = workspaceManager;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 当前调用方的用户标识。
     * <p>单用户模式下固定返回 {@code local}；接入认证后改为从 SecurityContext / 会话中取。
     */
    public String currentUserId() {
        return AppConstants.DEFAULT_USER_ID;
    }

    /**
     * 校验 ID 格式是否合法（不查库）。
     *
     * @param field 字段名，用于错误文案
     * @throws ApiExceptions.BadRequestException ID 为空或含非法字符
     */
    public void checkIdFormat(String field, String id) {
        if (id == null || id.isBlank()) {
            throw new ApiExceptions.BadRequestException("缺少 " + field);
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ApiExceptions.BadRequestException("非法 " + field + "（仅允许字母数字、-、_，长度 1~64）");
        }
    }

    /**
     * 校验工作区存在且归属当前用户。
     *
     * @return 该工作区上下文，供调用方复用（避免重复 getWorkspace）
     */
    public WorkspaceContext checkWorkspace(String workspaceId) {
        checkIdFormat("workspaceId", workspaceId);
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) {
            throw new ApiExceptions.NotFoundException("工作区不存在: " + workspaceId);
        }
        String owner = ws.getUserId() == null ? AppConstants.DEFAULT_USER_ID : ws.getUserId();
        if (!Objects.equals(owner, currentUserId())) {
            throw new ApiExceptions.ForbiddenException("无权访问该工作区");
        }
        return ws;
    }

    /**
     * 校验工作区归属 + 会话归属（会话必须挂在该工作区下）。
     * <p>
     * 尚未落库的新会话（前端先发首条消息、后端在 streamChat 里才创建）予以放行：
     * 此时 sessionId 在库中查不到，但它也不可能属于别人——真正的越权风险是
     * 「引用一个已存在且属于他人的 sessionId」，这里正是拦住这种情况。
     *
     * @param requireExisting true 表示会话必须已存在（如 history / stop 这类只读或操作既有会话的端点）
     */
    public WorkspaceContext checkSession(String workspaceId, String sessionId, boolean requireExisting) {
        WorkspaceContext ws = checkWorkspace(workspaceId);
        checkIdFormat("sessionId", sessionId);
        if (sessionRepository.existsByIdAndWorkspaceId(sessionId, workspaceId)) {
            return ws;
        }
        // 会话不在该工作区下：要么根本不存在，要么属于别的工作区——后者必须拒绝
        boolean existsElsewhere = sessionRepository.existsById(sessionId);
        if (existsElsewhere) {
            throw new ApiExceptions.ForbiddenException("会话不属于该工作区");
        }
        if (requireExisting) {
            throw new ApiExceptions.NotFoundException("会话不存在: " + sessionId);
        }
        return ws;
    }
}
