package com.xinl.easyclaw.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.domain.io.UserInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.ChatResponse;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.agent.embabel.AgentProcessEventBridge;
import com.xinl.easyclaw.agent.embabel.OrchestratorAgent;
import com.xinl.easyclaw.agent.embabel.ProcessLifecycleEvent;
import com.xinl.easyclaw.agent.embabel.ProcessOptionsFactory;
import com.xinl.easyclaw.agent.embabel.RoleAgentFactory;
import com.xinl.easyclaw.agent.embabel.SkillLoader;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.HistoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.MemoryContextData;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData.McpToolBridge;
import com.xinl.easyclaw.agent.embabel.domain.WorkspaceContextData.SubagentDeclaration;
import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.memory.entity.PropositionEntity;
import com.xinl.easyclaw.memory.service.MemoryExtractor;
import com.xinl.easyclaw.memory.service.MemoryService;
import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.scenario.ActionRegistry;
import com.xinl.easyclaw.scenario.ScenarioService;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.entity.SessionMessageEntity;
import com.xinl.easyclaw.workspace.repository.SessionMessageRepository;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> BUILTIN_ROLE_NAMES = Set.of(
            "orchestrator", "default", "file", "file-agent",
            "web", "web-agent", "code", "code-agent",
            "coding", "coding-agent"
    );

    private final WorkspaceManager workspaceManager;
    private final RoleAgentFactory roleAgentFactory;
    private final AgentPlatform agentPlatform;
    private final AgentProcessEventBridge eventBridge;
    private final SkillLoader skillLoader;
    private final PermissionRuleService permissionService;
    private final SessionMessageRepository messageRepo;
    private final RoleManagementService roleService;
    private final ToolRegistryService toolRegistryService;
    private final McpConnectionService mcpService;
    private final SubagentLoader subagentLoader;
    private final MemoryService memoryService;
    private final MemoryExtractor memoryExtractor;
    private final ActionRegistry actionRegistry;
    private final ScenarioService scenarioService;
    private final OrchestratorAgent orchestratorAgent;

    private final Map<String, AgentProcess> sessionProcesses = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToRootProcess = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLastAction = new ConcurrentHashMap<>();
    private final Map<String, String> sessionWorkspaces = new ConcurrentHashMap<>();
    /** 会话终止闸门：COMPLETED/FAILED/STUCK 触发后置 true，后续事件全部丢弃，防止"前端已结束还在产出事件" */
    private final Set<String> sessionTerminated = ConcurrentHashMap.newKeySet();
    /** 当前请求正在处理的工作区（用于记忆召回隔离；本地单用户场景足够） */
    private String currentWorkspaceId;

    public AgentService(WorkspaceManager workspaceManager,
                        @Lazy RoleAgentFactory roleAgentFactory,
                        AgentPlatform agentPlatform,
                        AgentProcessEventBridge eventBridge,
                        SkillLoader skillLoader,
                        PermissionRuleService permissionService,
                        SessionMessageRepository messageRepo,
                        RoleManagementService roleService,
                        ToolRegistryService toolRegistryService,
                        McpConnectionService mcpService,
                        SubagentLoader subagentLoader,
                        MemoryService memoryService,
                        MemoryExtractor memoryExtractor,
                        ActionRegistry actionRegistry,
                        ScenarioService scenarioService,
                        @Lazy OrchestratorAgent orchestratorAgent) {
        this.workspaceManager = workspaceManager;
        this.roleAgentFactory = roleAgentFactory;
        this.agentPlatform = agentPlatform;
        this.eventBridge = eventBridge;
        this.skillLoader = skillLoader;
        this.permissionService = permissionService;
        this.messageRepo = messageRepo;
        this.roleService = roleService;
        this.toolRegistryService = toolRegistryService;
        this.mcpService = mcpService;
        this.subagentLoader = subagentLoader;
        this.memoryService = memoryService;
        this.memoryExtractor = memoryExtractor;
        this.actionRegistry = actionRegistry;
        this.scenarioService = scenarioService;
        this.orchestratorAgent = orchestratorAgent;
    }

    // ==================== 权限系统 ====================

    public void allowTurn(String sessionId, Collection<String> toolNames) {
        log.info("allowTurn (per-turn no-op): sessionId={}, tools={}", sessionId, toolNames);
    }

    public void allowPermanently(String workspaceId, Collection<String> toolNames) {
        log.info("allowPermanently: workspaceId={}, tools={}", workspaceId, toolNames);
        for (String tool : toolNames) {
            permissionService.allow(workspaceId, tool, "user");
        }
    }

    public void revokePermanently(String workspaceId, String toolName) {
        log.info("revokePermanently: workspaceId={}, tool={}", workspaceId, toolName);
        permissionService.remove(workspaceId, toolName);
    }

    public List<Map<String, Object>> pendingConfirmInfo(String sessionId) {
        return List.of();
    }

    public List<PermissionRuleEntity> permanentRules(String workspaceId) {
        return permissionService.findForWorkspace(workspaceId);
    }

    // ==================== 停止 / 状态 ====================

    public void stopChat(String workspaceId, String sessionId) {
        log.info("停止会话流: workspaceId={}, sessionId={}", workspaceId, sessionId);

        // 先 kill 所有关联进程（含 Repository 兜底的未跟踪后代）
        Set<String> allProcessIds = eventBridge.getAllProcessIdsForSession(sessionId);
        log.info("准备 kill 所有关联进程: sessionId={}, count={}, ids={}", sessionId, allProcessIds.size(), allProcessIds);
        for (String pid : allProcessIds) {
            try {
                agentPlatform.killAgentProcess(pid);
                log.info("已 kill AgentProcess: id={}", pid);
            } catch (Exception e) {
                log.warn("kill AgentProcess 异常: id={}, err={}", pid, e.getMessage());
            }
        }

        // 清理本地映射
        sessionProcesses.remove(sessionId);
        sessionToRootProcess.remove(sessionId);
        eventBridge.unregister(sessionId);
        sessionLastAction.remove(sessionId);
        sessionWorkspaces.remove(sessionId);
        sessionTerminated.remove(sessionId);

        // 延迟二次 kill：防止第一次 kill 期间有新子进程被创建
        scheduleDelayedKill(sessionId, allProcessIds);
    }

    /** 延迟 3 秒后二次 kill——覆盖第一次 kill 期间可能新建的子进程 */
    private void scheduleDelayedKill(String sessionId, Set<String> alreadyKilled) {
        CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            try {
                Set<String> stillAlive = eventBridge.getAllProcessIdsForSession(sessionId);
                stillAlive.removeAll(alreadyKilled);
                if (!stillAlive.isEmpty()) {
                    log.warn("延迟二次 kill 发现新子进程: sessionId={}, ids={}", sessionId, stillAlive);
                    for (String pid : stillAlive) {
                        try {
                            agentPlatform.killAgentProcess(pid);
                            log.info("二次 kill AgentProcess: id={}", pid);
                        } catch (Exception e) {
                            log.warn("二次 kill 异常: id={}, err={}", pid, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("延迟二次 kill 异常: sessionId={}, err={}", sessionId, e.getMessage());
            }
        });
    }

    public Map<String, Object> getSessionStatus(String sessionId) {
        AgentProcess process = sessionProcesses.get(sessionId);
        boolean running = process != null && !process.getFinished();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running);
        result.put("pending", false);
        result.put("pendingTools", List.of());
        return result;
    }

    // ==================== 流式对话（核心） ====================

    public void streamChat(String workspaceId, String sessionId, String message,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, List.of(), null, onEvent, onError, onFinish);
    }

    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, attachments, null, onEvent, onError, onFinish);
    }

    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           String skillName,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        log.info("streamChat: workspaceId={}, sessionId={}, skill={}, msgLen={}",
                workspaceId, sessionId, skillName, message.length());

        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            onError.accept(new RuntimeException("❌ 工作区未找到: " + workspaceId));
            return;
        }
        this.currentWorkspaceId = workspace.getWorkspaceId();

        sessionWorkspaces.put(sessionId, workspaceId);
        sessionLastAction.remove(sessionId);
        sessionTerminated.remove(sessionId);

        AgentProcess old = sessionProcesses.remove(sessionId);
        if (old != null) {
            try { agentPlatform.killAgentProcess(old.getId()); } catch (Exception ignored) {}
            eventBridge.unregister(sessionId);
        }

        // ====== 1. 解析角色/Agent ======
        AgentRoleEntity roleEntity = resolveRole(skillName);
        String resolvedAgentKey;
        if (roleEntity != null) {
            resolvedAgentKey = "orchestrator";
            log.info("streamChat: 使用角色 '{}' (model={})", roleEntity.getName(), roleEntity.getModel());
        } else {
            resolvedAgentKey = resolveAgentKey(skillName);
        }
        // 通用 orchestrator/default → 根据场景 ID 选对应场景专用 Agent
        if ("orchestrator".equals(resolvedAgentKey) || "default".equals(resolvedAgentKey)) {
            String scenarioAgentKey = resolveAgentKeyForScenario(workspace.getScenarioId());
            if (scenarioAgentKey != null) {
                resolvedAgentKey = scenarioAgentKey;
                log.info("streamChat: 场景专用 agent, scenarioId={}, agentKey={}", workspace.getScenarioId(), resolvedAgentKey);
            }
        }
        var agent = roleAgentFactory.resolveAgent(resolvedAgentKey);

        // ====== 2. 构建 WorkspaceContextData（携带全部动态配置） ======
        WorkspaceContextData ctxData = buildContextData(workspace, roleEntity);

        // ====== 3. 如果用户指定了 skillName，在 prompt 前拼提示让 LLM 先加载 ======
        String finalPrompt = message;
        if (skillName != null && !skillName.isBlank() && !isBuiltinRoleName(skillName)) {
            finalPrompt = "请先调用 skill_load 工具加载技能 \"" + skillName + "\" 的 SKILL.md，然后严格按照该技能的规范执行以下任务。\n\n---\n\n" + message;
            log.info("streamChat: 在 prompt 前注入 skill_load 提示 skill={}", skillName);
        }

        // ====== 4. 查询历史消息（在 saveMessage 之前，排除本轮未完成的对话） ======
        List<HistoryContextData.HistoryMessage> historyMessages = loadHistoryMessages(sessionId);
        HistoryContextData historyCtx = new HistoryContextData(historyMessages);

        // ====== 5. 保存用户消息 ======
        final long[] seqHolder = {messageRepo.maxSeqForSession(sessionId)};
        saveMessage(sessionId, workspaceId, ++seqHolder[0], "USER", message, null, null, null);

        // ====== 6. 召回长期记忆（基于本轮用户消息） ======
        MemoryContextData memoryCtx = recallMemory(
                workspace.getUserId(),
                workspace.getWorkspaceId(),
                message
        );
        eventBridge.register(sessionId, evt -> handleEvent(sessionId, workspaceId, evt, onEvent,
                seqHolder, onFinish, message, workspace.getUserId()));
        eventBridge.bindWorkspace(sessionId, workspaceId);

        try {
            AgentProcess process = agentPlatform.createAgentProcessFrom(agent,
                    ProcessOptionsFactory.forRoot(eventBridge),
                    new UserInput(finalPrompt),
                    ctxData, historyCtx, memoryCtx);
            eventBridge.bindProcess(sessionId, process.getId());
            sessionProcesses.put(sessionId, process);
            sessionToRootProcess.put(sessionId, process.getId());
            agentPlatform.start(process);
            log.info("AgentProcess 已启动: processId={}, sessionId={}, workspacePath={}, role={}, historyMsgs={}, memories={}, skill={}",
                    process.getId(), sessionId, workspace.getPath(),
                    roleEntity != null ? roleEntity.getName() : "default",
                    historyMessages.size(), memoryCtx.items().size(), skillName);
        } catch (Exception e) {
            log.error("启动 AgentProcess 失败", e);
            eventBridge.unregister(sessionId);
            sessionProcesses.remove(sessionId);
            onError.accept(e);
        }
    }

    private List<HistoryContextData.HistoryMessage> loadHistoryMessages(String sessionId) {
        try {
            List<SessionMessageEntity> recent = messageRepo
                    .findTop30BySessionIdOrderBySeqDesc(sessionId);
            List<HistoryContextData.HistoryMessage> result = new ArrayList<>();
            for (int i = recent.size() - 1; i >= 0; i--) {
                SessionMessageEntity m = recent.get(i);
                String role = "USER".equalsIgnoreCase(m.getType()) ? "user"
                        : "ASSISTANT".equalsIgnoreCase(m.getType()) ? "assistant" : "system";
                if (m.getContent() != null && !m.getContent().isBlank()) {
                    result.add(new HistoryContextData.HistoryMessage(role, m.getContent()));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("加载历史消息失败: {}", e.getMessage());
            return List.of();
        }
    }

    private MemoryContextData recallMemory(String userId, String queryText) {
        return recallMemory(userId, currentWorkspaceId, queryText);
    }

    private MemoryContextData recallMemory(String userId, String workspaceId, String queryText) {
        try {
            if (userId == null || userId.isBlank() || queryText == null || queryText.isBlank()) {
                return new MemoryContextData(List.of());
            }
            List<PropositionEntity> recalled = memoryService.recall(userId, workspaceId, queryText, null);
            List<MemoryContextData.MemoryItem> items = recalled.stream()
                    .map(p -> new MemoryContextData.MemoryItem(
                            p.getContent(),
                            p.getType() != null ? p.getType().name() : "FACT",
                            p.getConfidence() != null ? p.getConfidence() : 0.5,
                            List.of()
                    ))
                    .toList();
            if (!items.isEmpty()) {
                log.info("记忆召回: userId={}, 召回 {} 条", userId, items.size());
            }
            return new MemoryContextData(items);
        } catch (Exception e) {
            log.warn("记忆召回失败: {}", e.getMessage());
            return new MemoryContextData(List.of());
        }
    }

    private ProcessLifecycleEvent handleEvent(String sessionId, String workspaceId,
                                              ProcessLifecycleEvent evt,
                                              Consumer<StreamEvent> onEvent,
                                              long[] seqHolder, Runnable onFinish,
                                              String userMessage, String userId) {
        String lastAction = sessionLastAction.get(sessionId);

        // ====== 终止闸门：会话已结束，直接丢弃所有后续事件 ======
        if (sessionTerminated.contains(sessionId)) {
            // 保留 log 方便定位"僵尸事件"，但不再转发给前端
            if (log.isTraceEnabled()) {
                log.trace("会话已终止，丢弃事件: sessionId={}, type={}", sessionId, evt.type());
            }
            return null;
        }

        try {
            switch (evt.type()) {
                case PLAN_FORMULATED -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> planData = (Map<String, Object>) m;
                        String planJson = MAPPER.writeValueAsString(planData);
                        onEvent.accept(StreamEvent.plan(planJson));
                        saveMessage(sessionId, workspaceId, ++seqHolder[0], "PLAN", planJson, null, null, null);

                        // ====== Plan 校验 ======
                        ScenarioEntity scenario = findScenarioForWorkspace(workspaceId);
                        validatePlanActions(planData, scenario, onEvent, sessionId, workspaceId, seqHolder);
                    }
                }
                case ACTION_START -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stepData = (Map<String, Object>) m;
                        String actionName = (String) stepData.get("name");
                        sessionLastAction.put(sessionId, actionName);
                        String json = MAPPER.writeValueAsString(stepData);
                        onEvent.accept(StreamEvent.agentAction(json));
                        onEvent.accept(StreamEvent.step(json));
                        // 子智能体（有 parentProcessId）：发送 subagent_lifecycle start
                        if (stepData.get("parentProcessId") != null) {
                            Map<String, Object> subLc = new LinkedHashMap<>();
                            subLc.put("name", actionName);
                            subLc.put("status", "start");
                            subLc.put("processId", stepData.get("processId"));
                            subLc.put("parentProcessId", stepData.get("parentProcessId"));
                            String subJson = MAPPER.writeValueAsString(subLc);
                            onEvent.accept(StreamEvent.subagentLifecycle(subJson));
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "SUBAGENT_LIFECYCLE", subJson, null, null, null);
                        }
                    }
                }
                case ACTION_END -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stepData = (Map<String, Object>) m;
                        String actionName = (String) stepData.get("name");
                        String json = MAPPER.writeValueAsString(stepData);
                        onEvent.accept(StreamEvent.agentAction(json));
                        onEvent.accept(StreamEvent.step(json));
                        saveMessage(sessionId, workspaceId, ++seqHolder[0], "STEP", json, null, null, null);
                    }
                }
                case TOOL_CALL_START, TOOL_CALL_END -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) m);
                        // tool name 统一小写：Embabel FileTools.readFile 在 ToolCallRequest/Response 中
                        // tool 字段有时会被 Embabel 内部自动转小写，导致 start=readfile vs end=readFile 断配。
                        // 统一把 data.tool 归一化，确保前端同一条工具的 start/end 标题完全一致，
                        // isEventActive 的 running→done 状态转换能正确对应。
                        Object rawTool = data.get("tool");
                        if (rawTool instanceof String s && !s.isBlank()) {
                            data.put("tool", s.toLowerCase());
                        }
                        data.put("type", evt.type().name().toLowerCase());
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(StreamEvent.toolCall(json));
                        if (evt.type() == ProcessLifecycleEvent.Type.TOOL_CALL_END) {
                            String tool = (String) data.get("tool");
                            Object in = data.get("input");
                            Object out = data.get("output");
                            String toolArgs = in != null ? in.toString() : null;
                            String toolResult = out != null ? out.toString() : null;
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "TOOL_CALL", json, tool, toolArgs, toolResult);
                        }
                    }
                }
                case LLM_CALL_START, LLM_CALL_END -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        data.put("type", evt.type().name().toLowerCase());
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(StreamEvent.llmCall(json));
                        if (evt.type() == ProcessLifecycleEvent.Type.LLM_CALL_END) {
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "LLM_CALL", json, null, null, null);
                        }
                    }
                }
                case LLM_INVOCATION -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        String json = MAPPER.writeValueAsString(m);
                        log.debug("LLM_INVOCATION: {}", json);
                    }
                }
                case LLM_THINKING -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        String thinkingContent = (String) data.get("thinkingContent");
                        if (thinkingContent != null && !thinkingContent.isBlank()) {
                            String json = MAPPER.writeValueAsString(data);
                            onEvent.accept(StreamEvent.reasoning(thinkingContent));
                            onEvent.accept(new StreamEvent("llm_thinking", json));
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "LLM_THINKING", json, null, null, null);
                        }
                    }
                }
                case PROGRESS_UPDATE -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(new StreamEvent("progress_update", json));
                    }
                }
                case GOAL_ACHIEVED -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(new StreamEvent("goal_achieved", json));
                    }
                }
                case TOOL_LOOP_START, TOOL_LOOP_COMPLETED -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        data.put("type", evt.type().name().toLowerCase());
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(new StreamEvent("tool_loop", json));
                    }
                }
                case STATE_TRANSITION -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(new StreamEvent("state_transition", json));
                    }
                }
                case REPLAN_REQUESTED -> {
                    if (evt.data() instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) m;
                        String reason = (String) data.get("reason");
                        String json = MAPPER.writeValueAsString(data);
                        onEvent.accept(new StreamEvent("replan_requested", json));
                        if (reason != null) {
                            onEvent.accept(StreamEvent.reasoning("🔄 重新规划: " + reason));
                        }
                    }
                }
                case PAUSED -> onEvent.accept(StreamEvent.reasoning("⏸️ 已暂停，等待用户确认..."));
                case WAITING -> onEvent.accept(StreamEvent.reasoning("⏳ 等待输入..."));
                case STUCK -> {
                    // 子进程卡住：只标记子 Agent 失败，不结束主会话
                    if (evt.data() instanceof Map<?, ?> m) {
                        Map<?, ?> data = (Map<?, ?>) m;
                        if (data.get("parentProcessId") != null) {
                            Map<String, Object> subLc = new LinkedHashMap<>();
                            subLc.put("name", data.get("agentName"));
                            subLc.put("status", "failed");
                            subLc.put("processId", data.get("processId"));
                            subLc.put("parentProcessId", data.get("parentProcessId"));
                            subLc.put("error", evt.data().toString());
                            onEvent.accept(StreamEvent.subagentLifecycle(MAPPER.writeValueAsString(subLc)));
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "SUBAGENT_LIFECYCLE",
                                    MAPPER.writeValueAsString(subLc), null, null, null);
                            return null;
                        }
                    }

                    String stuckMsg = evt.data() != null ? evt.data().toString() : "进程执行卡住";
                    // 先关闸门，再发最终消息（顺序不可变！防止 kill 期间继续穿事件）
                    sessionTerminated.add(sessionId);
                    onEvent.accept(StreamEvent.error("⚠️ " + stuckMsg));
                    saveMessage(sessionId, workspaceId, ++seqHolder[0], "SYSTEM", "⚠️ " + stuckMsg, null, null, null);
                    onEvent.accept(StreamEvent.end());
                    killChildrenForSession(sessionId);
                    onFinish.run();
                    cleanupSession(sessionId);
                }
                case COMPLETED -> {
                    // 子进程完成：只发 subagent_lifecycle end，不结束主会话
                    if (evt.data() instanceof Map<?, ?> m) {
                        Map<?, ?> data = (Map<?, ?>) m;
                        if (data.get("parentProcessId") != null) {
                            Map<String, Object> subLc = new LinkedHashMap<>();
                            subLc.put("name", data.get("agentName"));
                            subLc.put("status", "end");
                            subLc.put("processId", data.get("processId"));
                            subLc.put("parentProcessId", data.get("parentProcessId"));
                            onEvent.accept(StreamEvent.subagentLifecycle(MAPPER.writeValueAsString(subLc)));
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "SUBAGENT_LIFECYCLE",
                                    MAPPER.writeValueAsString(subLc), null, null, null);
                            return null;
                        }
                    }

                    Object result = evt.data();
                    log.info("COMPLETED: resultType={}, hasParentProcess={}, dataPreview={}",
                            result != null ? result.getClass().getName() : "null",
                            evt.data() instanceof Map<?,?> m ? m.get("parentProcessId") : "N/A",
                            result != null ? result.toString().substring(0, Math.min(result.toString().length(), 200)) : "null");
                    ChatResult cr = null;
                    if (result instanceof ChatResult r) {
                        cr = r;
                    } else {
                        AgentProcess proc = sessionProcesses.get(sessionId);
                        if (proc != null) {
                            cr = proc.resultOfType(ChatResult.class);
                        }
                    }
                    String replyText;
                    if (cr != null && cr.reply() != null) {
                        replyText = cr.reply();
                    } else if (result != null) {
                        replyText = result.toString();
                    } else {
                        replyText = "执行完成但无返回结果";
                    }
                    // 先关闸门，再发最终消息 + end（顺序不可变！防止 kill 期间继续穿事件）
                    sessionTerminated.add(sessionId);
                    onEvent.accept(StreamEvent.text(replyText));
                    saveMessage(sessionId, workspaceId, ++seqHolder[0], "AI_TEXT", replyText, null, null, null);
                    extractMemoryAsync(userId, workspaceId, userMessage, replyText);
                    onEvent.accept(StreamEvent.end());
                    // 级联 kill 所有子进程（根进程完成后子进程可能还在跑）
                    killChildrenForSession(sessionId);
                    onFinish.run();
                    cleanupSession(sessionId);
                }
                case FAILED -> {
                    // 子进程失败：只发 subagent_lifecycle end(failed)，不结束主会话
                    if (evt.data() instanceof Map<?, ?> m) {
                        Map<?, ?> data = (Map<?, ?>) m;
                        if (data.get("parentProcessId") != null) {
                            Map<String, Object> subLc = new LinkedHashMap<>();
                            subLc.put("name", data.get("agentName"));
                            subLc.put("status", "failed");
                            subLc.put("processId", data.get("processId"));
                            subLc.put("parentProcessId", data.get("parentProcessId"));
                            subLc.put("error", data.get("error") != null ? data.get("error") : evt.data().toString());
                            onEvent.accept(StreamEvent.subagentLifecycle(MAPPER.writeValueAsString(subLc)));
                            saveMessage(sessionId, workspaceId, ++seqHolder[0], "SUBAGENT_LIFECYCLE",
                                    MAPPER.writeValueAsString(subLc), null, null, null);
                            return null;
                        }
                    }

                    String errMsg = evt.data() != null ? evt.data().toString() : "未知错误";
                    // 先关闸门，再发最终消息 + end（顺序不可变！防止 kill 期间继续穿事件）
                    sessionTerminated.add(sessionId);
                    onEvent.accept(StreamEvent.error("❌ " + errMsg));
                    saveMessage(sessionId, workspaceId, ++seqHolder[0], "SYSTEM", "❌ " + errMsg, null, null, null);
                    extractMemoryAsync(userId, workspaceId, userMessage, null);
                    onEvent.accept(StreamEvent.end());
                    killChildrenForSession(sessionId);
                    onFinish.run();
                    cleanupSession(sessionId);
                }
            }
        } catch (Exception e) {
            log.error("处理生命周期事件异常: sessionId={}, err={}", sessionId, e.getMessage());
        }
        return null;
    }

    /** workspaceId → scenario；查不到/异常返回 null（resolvePlanConstraints 会用宽松默认） */
    private ScenarioEntity findScenarioForWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return null;
        try {
            var workspace = workspaceManager.getWorkspace(workspaceId);
            if (workspace == null || workspace.getScenarioId() == null) return null;
            return scenarioService.findById(workspace.getScenarioId()).orElse(null);
        } catch (Exception e) {
            log.warn("查找工作区场景失败: workspaceId={}, err={}", workspaceId, e.getMessage());
            return null;
        }
    }

    /**
     * 【关键修复：onlyOnce / orderRules / firstStepCannotBe 完全失效的根因】
     * ScenarioService 约束里填的是 @Export(name = "xxx") 的 kebab-case 名（code-task / file-task / orchestrate）
     * 但 GOAP plan 里 step.get("name") 实际生成的是方法名（驼峰 codeTask / fileTask / chat）→ 两边对不上，约束全失灵。
     * 统一成"标准检测名"再做比较：
     *   chat → orchestrate
     *   codeTask → code-task
     *   fileTask → file-task
     *   dataTask → data-task
     *   contentTask → content-task
     *   mailTask → mail-task
     *   researchTask → research （注：不是 research-task，和 export name 一致）
     *   devopsTask → devops-task
     *   verifyTask → verify-task
     *   interactionTask → interaction-task
     *   本身就是 kebab / 已标准名 → 原样返回
     */
    static String normalizeActionName(String name) {
        if (name == null) return "";
        String n = name.strip();
        // 先看特例（chat / 驼峰特例 / 收尾新方法 finalizeTask 都映射到 orchestrate，因为 Export name=orchestrate）
        return switch (n) {
            case "chat" -> "orchestrate";
            case "finalizeTask" -> "orchestrate";
            case "codeTask" -> "code-task";
            case "fileTask" -> "file-task";
            case "dataTask" -> "data-task";
            case "contentTask" -> "content-task";
            case "mailTask" -> "mail-task";
            case "researchTask" -> "research";
            case "devopsTask" -> "devops-task";
            case "verifyTask" -> "verify-task";
            case "interactionTask" -> "interaction-task";
            default -> n;  // 已经是 kebab / orchestrate / research / code-task 等标准名直接保留
        };
    }

    /**
     * Plan 校验（2 层）：
     *  Layer 1 基础：step.name 是否在 ActionRegistry 中注册
     *  Layer 2 场景级（按用户说"每个场景 pre 不一样"动态约束）：
     *    2.1 maxSteps：步数超上限 → 拒
     *    2.2 firstStepCannotBe：首 step 命中场景黑名单（如 orchestrate/verify/interaction）→ 拒
     *    2.3 onlyOnce：计划内出现重复 action → 拒（消除 plan 冗余）
     *    2.4 orderRules：若 B 要求 A 必须先执行，检查 B 的索引前是否存在任一 A → 没有则拒
     * 校验失败：返回 false + 写 PLAN_VALIDATION message + StreamEvent.error 警告，
     *   不 kill 进程（让 Embabel 自然 REPLAN → 失败几次 planner 就会收敛到合法路径）
     *
     * @return true 全通过；false 任一失败（仅日志）
     */
    @SuppressWarnings("unchecked")
    private boolean validatePlanActions(Map<String, Object> planData,
                                         ScenarioEntity scenario,
                                         Consumer<StreamEvent> onEvent,
                                         String sessionId, String workspaceId,
                                         long[] seqHolder) {
        Object stepsObj = planData.get("steps");
        if (!(stepsObj instanceof List<?> steps) || steps.isEmpty()) return true;

        Set<String> registeredActions = actionRegistry.getAll().keySet();
        List<String> invalidSteps = new ArrayList<>();
        List<String> validSteps = new ArrayList<>();
        List<String> normValidSteps = new ArrayList<>();  // 标准检测名（统一成 kebab / orchestrate / research）

        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) continue;
            String name = String.valueOf(step.get("name"));
            if (actionRegistry.isRegisteredAction(name)) {
                validSteps.add(name);
                normValidSteps.add(normalizeActionName(name));
            } else {
                invalidSteps.add(name);
            }
        }

        Map<String, Object> constraints = scenarioService.resolvePlanConstraints(scenario);
        int maxSteps = constraints.get("maxSteps") instanceof Integer i ? i : 5;
        // ===================== Layer 0.5：场景启用 Export 白名单（『场景有什么就执行什么』） =====================
        // coding 场景只允许 code-task / file-task / verify-task / interaction-task / orchestrate
        // mail / data / content / research / devops 这些未启用的 → 直接判 invalid，触发 REPLAN
        Set<String> normEnabledExports = scenario != null
                ? scenarioService.resolveEnabledExportNames(scenario)
                : new LinkedHashSet<>(ScenarioService.AGENT_TYPE_TO_EXPORT.values());
        if (!normEnabledExports.contains(ScenarioService.EXPORT_FINALIZE)) {
            normEnabledExports.add(ScenarioService.EXPORT_FINALIZE);
        }
        List<String> disabledSteps = new ArrayList<>();
        for (int i = 0; i < validSteps.size(); i++) {
            String norm = normValidSteps.get(i);
            if (!normEnabledExports.contains(norm)) {
                disabledSteps.add(validSteps.get(i) + "(" + norm + ")");
                invalidSteps.add(validSteps.get(i));
            }
        }
        // 约束列表（ScenarioService 里写的就是标准检测名：orchestrate / code-task / research 等）
        Set<String> normFirstCannotBe = constraints.get("firstStepCannotBe") instanceof List<?> l
                ? l.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()) : Set.of();
        Set<String> normOnlyOnce = constraints.get("onlyOnce") instanceof List<?> l
                ? l.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()) : Set.of();
        // orderRules 的 key / value 都是标准检测名；我们比较时先 normalize plan 侧的名
        Map<String, List<String>> orderRules = new LinkedHashMap<>();
        if (constraints.get("orderRules") instanceof Map<?, ?> om) {
            for (var kv : om.entrySet()) {
                if (kv.getValue() instanceof List<?> vl) {
                    orderRules.put(normalizeActionName(String.valueOf(kv.getKey())),
                            vl.stream().map(x -> normalizeActionName(String.valueOf(x))).toList());
                }
            }
        }

        List<String> reasonLines = new ArrayList<>();
        // Layer 0.5: 场景启用 Export 白名单未命中（优先提示用户能懂的能力名，不是底层 action 名）
        if (!disabledSteps.isEmpty()) {
            reasonLines.add("场景「" + (scenario != null && scenario.getName() != null ? scenario.getName() : "通用")
                    + "」未启用这些能力: " + disabledSteps
                    + "。当前仅允许: " + new ArrayList<>(normEnabledExports)
                    + "。请切换场景或在场景编辑器中启用对应 SubAgent。");
        }
        // Layer 1: 未注册 action
        if (!invalidSteps.isEmpty() && disabledSteps.isEmpty()) {
            reasonLines.add("包含未注册 action: " + invalidSteps);
        } else if (!invalidSteps.isEmpty()) {
            // disabledSteps 已经包含了 scenario 禁用的（也加到 invalidSteps 里了），所以此处区分一下
            List<String> trulyUnregistered = invalidSteps.stream()
                    .filter(s -> !disabledSteps.stream().anyMatch(d -> d.startsWith(s + "(")))
                    .toList();
            if (!trulyUnregistered.isEmpty()) {
                reasonLines.add("包含未注册 action: " + trulyUnregistered);
            }
        }
        // Layer 2.1 maxSteps 超上限（看原始数量，不用 normalize）
        if (validSteps.size() > maxSteps) {
            reasonLines.add("步数 " + validSteps.size() + " 超出场景上限 " + maxSteps);
        }
        // Layer 2.2 firstStep 黑名单（场景配置）→ 用 normalized 对比
        String first = validSteps.isEmpty() ? null : validSteps.get(0);
        String normFirst = normValidSteps.isEmpty() ? null : normValidSteps.get(0);
        if (normFirst != null && normFirstCannotBe.contains(normFirst)) {
            reasonLines.add("首 step (" + first + "/" + normFirst + ") 被场景禁止（请先选择实际工作 action）");
        }
        // Layer 2.25 全局硬规则：plan 长度 > 1 时首 step 绝对不能是 chat/orchestrate 兜底
        if (normFirst != null && normValidSteps.size() > 1) {
            if ("orchestrate".equals(normFirst)) {
                reasonLines.add("首 step 不能是 (" + first + "/" + normFirst + ") 兜底 action（plan 长度 "
                        + normValidSteps.size() + " > 1）。复杂任务请选专用委派 action（code-task/file-task/data-task 等）");
            }
        }
        // Layer 2.3 onlyOnce 重复 → 用 normalized 对比（原 codeTask / code-task 现在都会被归到 code-task 计数）
        java.util.Map<String, Long> cntNorm = normValidSteps.stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()));
        for (String a : normOnlyOnce) {
            if (cntNorm.getOrDefault(a, 0L) > 1L) {
                reasonLines.add("action " + a + " 重复出现 " + cntNorm.get(a) + " 次（场景要求唯一）");
            }
        }
        // Layer 2.4 orderRules 有向边 → 用 normalized 下标查找
        for (var entry : orderRules.entrySet()) {
            String after = entry.getKey();
            List<String> mustBeBefore = entry.getValue();
            int idxAfter = normValidSteps.lastIndexOf(after);
            if (idxAfter <= 0) continue;  // 没出现在 plan 中或就是首位 -> 跳过
            boolean anyBefore = mustBeBefore.stream()
                    .anyMatch(before -> normValidSteps.subList(0, idxAfter).contains(before));
            if (!anyBefore) {
                reasonLines.add(after + " 前面必须存在 " + mustBeBefore
                        + " 中至少一个 action（当前 plan 中它位于第 " + (idxAfter + 1) + " 步）");
            }
        }

        if (reasonLines.isEmpty()) {
            log.info("Plan 校验通过: scenario={}, steps={}, size={} (≤max={})",
                    scenario != null ? scenario.getId() : "<default>", validSteps, validSteps.size(), maxSteps);
            return true;
        }

        log.warn("Plan 校验失败: scenario={}, invalid={}, valid={}, reasons={}",
                scenario != null ? scenario.getId() : "<default>",
                invalidSteps.size(), validSteps.size(), reasonLines);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("valid", false);
        validation.put("invalidSteps", invalidSteps);
        validation.put("validSteps", validSteps);
        validation.put("availableActions", new ArrayList<>(registeredActions));
        validation.put("maxSteps", maxSteps);
        validation.put("firstStepCannotBe", new ArrayList<>(normFirstCannotBe));
        validation.put("onlyOnce", new ArrayList<>(normOnlyOnce));
        validation.put("orderRules", orderRules);
        validation.put("violations", reasonLines);
        validation.put("message", "Plan 违反 " + reasonLines.size() + " 条约束，正在触发重新规划：\n  • " + String.join("\n  • ", reasonLines));

        try {
            String json = MAPPER.writeValueAsString(validation);
            onEvent.accept(StreamEvent.error("⚠️ Plan 校验重新规划: " + json));
            saveMessage(sessionId, workspaceId, ++seqHolder[0], "PLAN_VALIDATION",
                    json, null, null, null);
        } catch (Exception e) {
            log.warn("Plan 校验结果序列化失败: {}", e.getMessage());
        }

        // 不 kill 进程——让 Embabel 自然 FAILED/REPLAN，避免前端/后端状态不一致
        return false;
    }

    private void extractMemoryAsync(String userId, String workspaceId, String userMessage, String aiResponse) {
        if (userId == null || userId.isBlank()) return;
        CompletableFuture.runAsync(() -> {
            try {
                int extracted = memoryExtractor.extractFromConversation(userId, workspaceId, userMessage, aiResponse);
                if (extracted > 0) {
                    log.info("记忆抽取: userId={}, 抽取 {} 条", userId, extracted);
                }
            } catch (Exception e) {
                log.warn("记忆抽取失败: {}", e.getMessage());
            }
        });
    }

    public void resumeChat(String workspaceId, String sessionId, boolean approved,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        AgentProcess process = sessionProcesses.get(sessionId);
        if (process == null) {
            log.warn("resumeChat: 找不到运行中的 process, sessionId={}", sessionId);
            onError.accept(new RuntimeException("没有挂起的会话: " + sessionId));
            return;
        }

        log.info("resumeChat: sessionId={}, approved={}, processId={}", sessionId, approved, process.getId());

        try {
            if (approved) {
                if (process.getFinished()) {
                    ChatResult cr = process.resultOfType(ChatResult.class);
                    if (cr != null && cr.reply() != null) {
                        onEvent.accept(StreamEvent.text(cr.reply()));
                    }
                    onFinish.run();
                    cleanupSession(sessionId);
                } else {
                    agentPlatform.start(process);
                }
            } else {
                agentPlatform.killAgentProcess(process.getId());
                onEvent.accept(StreamEvent.text("已取消执行。"));
                onFinish.run();
                cleanupSession(sessionId);
            }
        } catch (Exception e) {
            log.error("resumeChat 失败", e);
            onError.accept(e);
        }
    }

    private void cleanupSession(String sessionId) {
        sessionProcesses.remove(sessionId);
        sessionToRootProcess.remove(sessionId);
        sessionLastAction.remove(sessionId);
        eventBridge.unregister(sessionId);
    }

    /** 级联 kill 某 session 下所有子进程（不清理 sessionProcesses 本身，cleanupSession 会做） */
    private void killChildrenForSession(String sessionId) {
        Set<String> all = eventBridge.getAllProcessIdsForSession(sessionId);
        String rootId = sessionToRootProcess.get(sessionId);
        Set<String> killed = new LinkedHashSet<>();
        for (String pid : all) {
            if (pid.equals(rootId)) continue;
            try {
                agentPlatform.killAgentProcess(pid);
                killed.add(pid);
                log.info("已 kill 子进程: sessionId={}, pid={}", sessionId, pid);
            } catch (Exception e) {
                log.warn("kill 子进程异常: sessionId={}, pid={}, err={}", sessionId, pid, e.getMessage());
            }
        }
        // 子进程创建可能在父进程结束瞬间发生，延迟兜底
        scheduleDelayedKill(sessionId, killed);
    }

    private void saveMessage(String sessionId, String workspaceId, long seq, String type,
                             String content, String toolName, String toolArgs, String toolResult) {
        try {
            String clean = content == null ? "" : content;

            // =========================================
            // ★ 15 秒时间窗去重（根治『AI 消息一模一样显示两遍』）
            // 典型场景：Embabel Path A 单步 + Path B plan 收尾都调 finalizeTask，两次相同内容 saveMessage
            // 规则：如果近 15 秒内同 session 已存在一条 type 相同、内容相同（或前 100 字符相同）的消息，直接跳过
            // =========================================
            Set<String> skipDedupTypes = Set.of("PLAN", "PLAN_VALIDATION", "TOOL_CALL", "TOOL_RESULT");
            if (sessionId != null && !skipDedupTypes.contains(type)) {
                try {
                    List<SessionMessageEntity> recent = messageRepo.findTop30BySessionIdOrderBySeqDesc(sessionId);
                    if (recent != null && !recent.isEmpty()) {
                        Instant window = Instant.now().minusSeconds(15);
                        String cleanKey = clean.strip();
                        int keyPrefix = Math.min(cleanKey.length(), 120);
                        String prefixKey = keyPrefix > 0 ? cleanKey.substring(0, keyPrefix) : "";
                        for (int i = 0; i < Math.min(5, recent.size()); i++) {
                            SessionMessageEntity m = recent.get(i);
                            if (m == null) continue;
                            if (!type.equalsIgnoreCase(String.valueOf(m.getType()))) continue;
                            if (m.getCreatedAt() == null || m.getCreatedAt().isBefore(window)) break; // 再往前都是更旧的，直接跳出
                            String exist = m.getContent() == null ? "" : m.getContent().strip();
                            // 两种判重：完全相等 OR 前 120 字符相等（防止末尾 \n 差异）
                            if (exist.equals(cleanKey)
                                    || (!prefixKey.isBlank() && exist.startsWith(prefixKey) && cleanKey.startsWith(prefixKey))) {
                                log.debug("saveMessage 跳过重复(15s窗): session={}, type={}, prefixLen={}", sessionId, type, keyPrefix);
                                return;
                            }
                        }
                    }
                } catch (Exception dedupeE) {
                    // 判重失败不影响正常 save，只是可能重复，记 debug 即可
                    log.debug("saveMessage 判重过程异常（继续正常保存）: {}", dedupeE.getMessage());
                }
            }

            SessionMessageEntity msg = SessionMessageEntity.builder()
                    .sessionId(sessionId)
                    .workspaceId(workspaceId)
                    .seq(seq)
                    .type(type)
                    .content(clean)
                    .toolName(toolName)
                    .toolArgs(toolArgs)
                    .toolResult(toolResult)
                    .createdAt(Instant.now())
                    .build();
            messageRepo.save(msg);
        } catch (Exception e) {
            log.warn("保存消息失败: sessionId={}, seq={}, err={}", sessionId, seq, e.getMessage());
        }
    }

    public ChatResponse chat(String workspaceId, String sessionId, String message) {
        return chat(workspaceId, sessionId, message, null);
    }

    public ChatResponse chat(String workspaceId, String sessionId, String message, String skillName) {
        log.info("AgentService.chat: workspaceId={}, sessionId={}, skill={}, msgLen={}",
                workspaceId, sessionId, skillName, message.length());
        try {
            WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
            if (workspace == null) {
                return new ChatResponse("❌ 工作区未找到: " + workspaceId, "markdown", "error");
            }
              this.currentWorkspaceId = workspace.getWorkspaceId();

            AgentRoleEntity roleEntity = resolveRole(skillName);
            String resolvedAgentKey;
            if (roleEntity != null) {
                resolvedAgentKey = "orchestrator";
            } else {
                resolvedAgentKey = resolveAgentKey(skillName);
            }
            if ("orchestrator".equals(resolvedAgentKey) || "default".equals(resolvedAgentKey)) {
                String scenarioAgentKey = resolveAgentKeyForScenario(workspace.getScenarioId());
                if (scenarioAgentKey != null) {
                    resolvedAgentKey = scenarioAgentKey;
                }
            }
            var agent = roleAgentFactory.resolveAgent(resolvedAgentKey);

            WorkspaceContextData ctxData = buildContextData(workspace, roleEntity);

            List<HistoryContextData.HistoryMessage> historyMsgs = loadHistoryMessages(sessionId);
            HistoryContextData historyCtx = new HistoryContextData(historyMsgs);

            MemoryContextData memoryCtx = recallMemory(
                    workspace.getUserId(),
                    workspace.getWorkspaceId(),
                    message
            );

            String finalPrompt = message;
            if (skillName != null && !skillName.isBlank() && !isBuiltinRoleName(skillName)) {
                finalPrompt = "请先调用 skill_load 工具加载技能 \"" + skillName + "\" 的 SKILL.md，然后严格按照该技能的规范执行以下任务。\n\n---\n\n" + message;
            }

            ChatResult result = runSync(agent, finalPrompt, ctxData, historyCtx, memoryCtx);

            extractMemoryAsync(workspace.getUserId(),workspaceId, message,
                    result != null ? result.reply() : null);

            if (result != null && result.reply() != null) {
                return new ChatResponse(result.reply(), "markdown",
                        result.modelUsed() != null ? result.modelUsed() : "embabel-agent");
            }
            return new ChatResponse("执行完成但无返回结果", "markdown",
                    skillName != null ? skillName : "embabel-agent");
        } catch (Exception e) {
            log.error("Agent 执行失败: {}", e.getMessage(), e);
            return new ChatResponse(
                    "❌ AI 助手执行失败: " + e.getMessage() + "\n\n请检查模型配置或 API Key 后重试。", "markdown", "error");
        }
    }

    private ChatResult runSync(com.embabel.agent.core.Agent agent, String message,
                               WorkspaceContextData ctxData,
                               HistoryContextData historyCtx,
                               MemoryContextData memoryCtx) {
        Map<String, Object> blackboard = new LinkedHashMap<>();
        blackboard.put("input", new UserInput(message));
        blackboard.put("workspaceCtx", ctxData);
        blackboard.put("historyCtx", historyCtx);
        blackboard.put("memoryCtx", memoryCtx);
        AgentProcess process = agentPlatform.runAgentFrom(agent,
                ProcessOptionsFactory.forRoot(), blackboard);
        return process.resultOfType(ChatResult.class);
    }

    // ==================== 解析逻辑 ====================

    /**
     * 查找数据库中定义的活跃角色（精确匹配 name）。
     * 找不到返回 null（此时 skillName 可能是 skill 文件名或硬编码角色名）。
     */
    private AgentRoleEntity resolveRole(String skillName) {
        if (skillName == null || skillName.isBlank()) return null;
        try {
            return roleService.findByName(skillName)
                    .filter(r -> Boolean.TRUE.equals(r.getActive()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("查询角色失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据 skillName 确定要用哪个硬编码 Agent。
     * 如果 skillName 是硬编码角色名 → 用对应的 Agent
     * 否则 → 回退到 orchestrator
     */
    private String resolveAgentKey(String skillName) {
        if (skillName == null || skillName.isBlank()) return "orchestrator";
        String lower = skillName.toLowerCase();
        if (BUILTIN_ROLE_NAMES.contains(lower)) {
            return lower;
        }
        return "orchestrator";
    }

    /** 场景 ID → 场景专用 agentType 映射（未列出的场景回退到 orchestrator） */
    private static final Map<String, String> SCENARIO_TO_AGENT_TYPE = Map.of(
            "preset_coding", "coding-scenario",
            "preset_weekly-report", "weekly-report-scenario",
            "preset_content-create", "content-create-scenario",
            "preset_mail-triage", "mail-triage-scenario",
            "preset_data-analysis", "data-analysis-scenario",
            "preset_devops", "devops-scenario"
    );

    /**
     * 根据 workspace.scenarioId 解析场景专用 agent key。
     * 返回 null 表示该场景无专用 Agent，回退到 orchestrator。
     */
    private String resolveAgentKeyForScenario(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) return null;
        return SCENARIO_TO_AGENT_TYPE.get(scenarioId);
    }

    private boolean isBuiltinRoleName(String name) {
        if (name == null) return false;
        return BUILTIN_ROLE_NAMES.contains(name.toLowerCase());
    }

    /**
     * 组装完整的 WorkspaceContextData，包含角色配置、禁用工具、MCP 工具、子智能体声明。
     */
    private WorkspaceContextData buildContextData(WorkspaceContext workspace, AgentRoleEntity roleEntity) {
        WorkspaceContextData base = new WorkspaceContextData(
                workspace.getWorkspaceId(), workspace.getPath(), workspace.getUserId());

        // 角色配置
        if (roleEntity != null) {
            String systemPrompt = buildRoleSystemPrompt(roleEntity);
            base = base.withRole(roleEntity.getName(), systemPrompt,
                    roleEntity.getModel(), roleEntity.getTemperature());
            base = base.withAllowedAgentTypes(parseJsonList(roleEntity.getAllowedAgents()));
            base = base.withAllowedMcpTools(parseJsonList(roleEntity.getAllowedMcp()));
        }

        // 全局禁用的工具（ToolDefinition.enabled=false 且非框架工具）
        List<String> disabled = toolRegistryService.disabledToolNames();
        base = base.withDisabledTools(disabled);

        // 工作区级权限：被用户永久拒绝的工具
        List<PermissionRuleEntity> denied = permissionService.findForWorkspace(workspace.getWorkspaceId());
        List<String> deniedTools = denied.stream()
                .filter(r -> "DENY".equalsIgnoreCase(r.getBehavior()))
                .map(PermissionRuleEntity::getToolName)
                .toList();
        if (!deniedTools.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(disabled);
            merged.addAll(deniedTools);
            base = base.withDisabledTools(List.copyOf(merged));
        }

        // MCP HTTP_TOOL 桥接
        List<McpToolBridge> mcpBridges = collectMcpBridges(workspace.getWorkspaceId());
        if (!mcpBridges.isEmpty()) {
            base = base.withMcpTools(mcpBridges);
        }

        // 子智能体声明（从文件加载）
        List<SubagentDeclaration> subagents = collectSubagentDeclarations(workspace);
        if (!subagents.isEmpty()) {
            base = base.withSubagents(subagents);
        }

        // Workspace intent + activeSkills（PresetIntent 默认 or 用户自定义）
        String intent = workspace.getIntent() != null ? workspace.getIntent() : "general";
        List<String> activeSkills = workspace.getActiveSkills();
        // 角色默认 Skill 合并进工作区 Skill，角色优先级高于默认 intent
        if (roleEntity != null && roleEntity.getDefaultSkills() != null && !roleEntity.getDefaultSkills().isBlank()) {
            try {
                List<String> roleSkills = MAPPER.readValue(roleEntity.getDefaultSkills(), new TypeReference<>() {});
                if (roleSkills != null && !roleSkills.isEmpty()) {
                    Set<String> merged = new LinkedHashSet<>(activeSkills == null ? List.of() : activeSkills);
                    merged.addAll(roleSkills);
                    activeSkills = List.copyOf(merged);
                }
            } catch (Exception e) {
                log.warn("解析角色默认 Skill 失败: role={}, err={}", roleEntity.getName(), e.getMessage());
            }
        }
        base = base.withIntent(intent, activeSkills);

        // Workspace 关联的场景
        base = base.withScenarioId(workspace.getScenarioId());

        return base;
    }

    private String buildRoleSystemPrompt(AgentRoleEntity role) {
        StringBuilder sb = new StringBuilder();
        if (role.getRole() != null && !role.getRole().isBlank()) {
            sb.append("## 角色\n").append(role.getRole()).append("\n\n");
        }
        if (role.getGoal() != null && !role.getGoal().isBlank()) {
            sb.append("## 目标\n").append(role.getGoal()).append("\n\n");
        }
        if (role.getBackstory() != null && !role.getBackstory().isBlank()) {
            sb.append("## 背景\n").append(role.getBackstory()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private List<McpToolBridge> collectMcpBridges(String workspaceId) {
        List<McpToolBridge> bridges = new ArrayList<>();
        try {
            List<McpServiceEntity> all = mcpService.findAll();
            for (McpServiceEntity mcp : all) {
                if (Boolean.TRUE.equals(mcp.getIsTemplate())) continue;
                // 过滤 scope：GLOBAL 全部可见；WORKSPACE 只在对应 workspace 可见
                if ("WORKSPACE".equalsIgnoreCase(mcp.getScope())
                        && !workspaceId.equals(mcp.getWorkspaceId())) continue;
                if (!Boolean.TRUE.equals(mcp.getIsConnected())) continue;
                if (!"HTTP_TOOL".equalsIgnoreCase(mcp.resolveTransport())) continue;

                String config = mcp.getImplementationConfig();
                if (config == null || config.isBlank()) continue;

                try {
                    Map<String, Object> cfg = MAPPER.readValue(config, new TypeReference<>() {});
                    bridges.add(new McpToolBridge(
                            mcp.getName(),
                            mcp.getDescription() != null ? mcp.getDescription() : mcp.getName(),
                            (String) cfg.getOrDefault("method", "GET"),
                            (String) cfg.getOrDefault("urlTemplate", mcp.getUrl() != null ? mcp.getUrl() : ""),
                            (String) cfg.getOrDefault("bodyMode", "none"),
                            cfg.containsKey("bodyTemplate") ? MAPPER.writeValueAsString(cfg.get("bodyTemplate")) : null,
                            parseStringMap(mcp.getHeaders())
                    ));
                } catch (Exception e) {
                    log.warn("解析 MCP HTTP_TOOL 配置失败: name={}, err={}", mcp.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("收集 MCP 桥接工具失败: {}", e.getMessage());
        }
        return bridges;
    }


    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> list = MAPPER.readValue(json, new TypeReference<>() {});
            return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("解析 JSON 列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<SubagentDeclaration> collectSubagentDeclarations(WorkspaceContext workspace) {
        List<SubagentDeclaration> decls = new ArrayList<>();
        try {
            // 全局 subagents
            collectFromDir(SystemHomePaths.globalSubagentsDir(), decls);
            // workspace 级 subagents（覆盖同名）
            Path wsAgents = workspace.getPath().resolve(".easyClaw/agent/subagents");
            collectFromDir(wsAgents, decls);

            // 活跃角色作为动态子 Agent 参与协同（main 是主智能体，不作为子 Agent）
            for (AgentRoleEntity role : roleService.findActiveRoles()) {
                if ("main".equalsIgnoreCase(role.getName())) continue;
                String roleType = role.getRoleType() != null ? role.getRoleType().toUpperCase() : "MARKDOWN";
                String name = role.getName();
                String desc = role.getDisplayName() != null ? role.getDisplayName() : name;
                String systemPrompt = buildRoleSystemPrompt(role);
                // 同名角色覆盖文件型 subagent
                decls.removeIf(d -> d.name().equals(name));
                decls.add(new SubagentDeclaration(name, desc, systemPrompt, roleType, role.getAgentClassName()));
            }
        } catch (Exception e) {
            log.debug("收集子智能体声明失败: {}", e.getMessage());
        }
        return decls;
    }

    private void collectFromDir(Path dir, List<SubagentDeclaration> out) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".md", "");
                        String content = readSafe(p);
                        String desc = extractField(content, "description");
                        // 覆盖同名
                        out.removeIf(d -> d.name().equals(name));
                        out.add(new SubagentDeclaration(name, desc, stripFrontmatter(content)));
                    });
        } catch (Exception ignored) {}
    }

    private String readSafe(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private String extractField(String content, String field) {
        if (content == null) return "";
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith(field + ":")) {
                return t.substring(field.length() + 1).trim().replaceAll("^[\"']|[\"']$", "");
            }
        }
        return "";
    }

    private String stripFrontmatter(String raw) {
        if (raw == null) return "";
        String trimmed = raw.replaceFirst("^\\uFEFF?", "");
        if (!trimmed.startsWith("---")) return trimmed;
        int end = trimmed.indexOf("---", 3);
        if (end < 0) return trimmed;
        return trimmed.substring(end + 3).trim();
    }
}
