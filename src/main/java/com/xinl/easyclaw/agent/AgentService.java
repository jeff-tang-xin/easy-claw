package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.ChatResponse;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WorkspaceManager workspaceManager;
    private final AgentFactory agentFactory;
    private final PermissionRuleService permissionRuleService;
    /** 待用户确认的工具调用（按会话隔离） */
    private final Map<String, List<Object>> pendingConfirms = new ConcurrentHashMap<>();
    /** 本回合已允许的工具名（按会话隔离） */
    private final Map<String, Set<String>> turnAllowed = new ConcurrentHashMap<>();
    /** 会话 → Workspace 映射（权限判断按 workspace 隔离） */
    private final Map<String, String> sessionWorkspaces = new ConcurrentHashMap<>();
    /** 子 Agent 调度计数（sessionId → subagentName → 次数），用于循环调度防护 */
    private final Map<String, Map<String, Integer>> subagentCallCounts = new ConcurrentHashMap<>();
    /** 子 Agent 工具结果文本缓冲（source → 累积文本），用于 end 时推断真实状态 */
    private final Map<String, StringBuilder> subagentResultBuffers = new ConcurrentHashMap<>();
    /** 每个会话正在运行的流订阅（用于 stopChat 时 dispose 取消） */
    private final Map<String, Disposable> sessionDisposables = new ConcurrentHashMap<>();

    public AgentService(WorkspaceManager workspaceManager,
                        AgentFactory agentFactory,
                        PermissionRuleService permissionRuleService) {
        this.workspaceManager = workspaceManager;
        this.agentFactory = agentFactory;
        this.permissionRuleService = permissionRuleService;
    }

    public void allowTurn(String sessionId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        Set<String> set = turnAllowed.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        set.addAll(toolNames);
        log.info("本回合已允许工具: sessionId={}, tools={}", sessionId, toolNames);
    }

    public void allowPermanently(String workspaceId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        for (String name : toolNames) {
            try {
                permissionRuleService.allow(workspaceId, name, "user");
            } catch (Exception e) {
                log.warn("保存永久允许规则失败: {} - {}", name, e.getMessage());
            }
        }
        log.info("永久允许工具: workspaceId={}, tools={}", workspaceId, toolNames);
        try { workspaceManager.rebuildAgent(workspaceId); } catch (Exception e) { log.warn("重建 Agent 失败: {}", e.getMessage()); }
    }

    public void revokePermanently(String workspaceId, String toolName) {
        permissionRuleService.remove(workspaceId, toolName);
        log.info("已撤销永久授权: workspaceId={}, tool={}", workspaceId, toolName);
        try { workspaceManager.rebuildAgent(workspaceId); } catch (Exception e) { log.warn("重建 Agent 失败: {}", e.getMessage()); }
    }

    public List<PermissionRuleEntity> permanentRules(String workspaceId) {
        return permissionRuleService.findForWorkspace(workspaceId);
    }

    public List<Map<String, Object>> pendingConfirmInfo(String sessionId) {
        List<Object> tools = pendingConfirms.get(sessionId);
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>();
    }

    public void stopChat(String workspaceId, String sessionId) {
        log.info("停止会话流: workspaceId={}, sessionId={}", workspaceId, sessionId);

        /* TODO: migrate to Embabel - RetryableHttpTransport.abortAll() removed */

        Disposable disp = sessionDisposables.remove(sessionId);
        if (disp != null && !disp.isDisposed()) {
            try {
                disp.dispose();
                log.info("已 dispose Flux 订阅: sessionId={}", sessionId);
            } catch (Exception e) {
                log.warn("dispose 订阅异常: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }

        /* TODO: migrate to Embabel - agent.interrupt() disabled */

        pendingConfirms.remove(sessionId);
        turnAllowed.remove(sessionId);
    }

    public Map<String, Object> getSessionStatus(String sessionId) {
        boolean running = false;
        Disposable disp = sessionDisposables.get(sessionId);
        if (disp != null && !disp.isDisposed()) {
            running = true;
        }
        List<Map<String, Object>> pendingTools = pendingConfirmInfo(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running);
        result.put("pending", !pendingTools.isEmpty());
        result.put("pendingTools", pendingTools);
        log.debug("查询会话状态: sessionId={}, running={}, pending={}", sessionId, running, !pendingTools.isEmpty());
        return result;
    }

    private boolean isAllowed(String sessionId, String toolName) {
        String workspaceId = sessionWorkspaces.get(sessionId);
        if (workspaceId != null && permissionRuleService.isAlwaysAllowed(workspaceId, toolName)) {
            return true;
        }
        Set<String> turn = turnAllowed.get(sessionId);
        return turn != null && turn.contains(toolName);
    }

    public void streamChat(String workspaceId, String sessionId, String message,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, List.of(),
                null,
                onEvent, onError, onFinish);
    }

    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, attachments,
                null,
                onEvent, onError, onFinish);
    }

    /* TODO: migrate to Embabel - Agent streaming disabled */
    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           String skillName,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        /* TODO: migrate to Embabel - RetryableHttpTransport.resetAll() removed */

        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            onError.accept(new RuntimeException("❌ 工作区未找到: " + workspaceId));
            return;
        }

        sessionWorkspaces.put(sessionId, workspaceId);
        subagentCallCounts.remove(sessionId);

        /* TODO: migrate to Embabel - clearStaleConfirmation / preconfigurePlanFile disabled */

        onError.accept(new RuntimeException("Agent streaming not implemented yet (pending Embabel migration)"));
    }

    public void resumeChat(String workspaceId, String sessionId, boolean approved,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        /* TODO: migrate to Embabel */
        onError.accept(new RuntimeException("Agent resumeChat not implemented yet (pending Embabel migration)"));
    }

    private String extractSubagentName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "子 Agent";
        }
        String s = raw.replace("subagent_", "").replace("subagent-", "");
        return s.isBlank() ? "子 Agent" : s;
    }

    public ChatResponse chat(String workspaceId, String sessionId, String message) {
        return chat(workspaceId, sessionId, message, null);
    }

    public ChatResponse chat(String workspaceId, String sessionId, String message, String roleName) {
        log.info("AgentService.chat: workspaceId={}, sessionId={}, role={}, message={}",
                workspaceId, sessionId, roleName, message.substring(0, Math.min(50, message.length())));

        try {
            WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
            if (workspace == null) {
                return new ChatResponse(
                        "❌ 工作区未找到: " + workspaceId,
                        "markdown",
                        "error"
                );
            }

            /* TODO: migrate to Embabel - agent.call() disabled */
            return new ChatResponse(
                    "Agent chat not implemented yet (pending Embabel migration)",
                    "markdown",
                    roleName != null ? roleName : "workspace-agent"
            );
        } catch (Exception e) {
            log.error("Agent 执行失败: {}", e.getMessage(), e);
            return new ChatResponse(
                    "❌ AI 助手执行失败: " + e.getMessage() + "\n\n请检查模型配置或 API Key 后重试。",
                    "markdown",
                    "error"
            );
        }
    }
}
