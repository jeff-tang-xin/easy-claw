package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.ActionMetadata;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.plan.Plan;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.ScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AgentProcess 事件桥接
 * <p>
 * Embabel 使用 {@link AgenticEventListener} 接口发布事件（而非 Spring ApplicationEventPublisher），
 * 必须通过 ProcessOptions.withListener() 注册。
 * <p>
 * 本组件实现 AgenticEventListener，将 Embabel 原生事件转换为业务层 {@link ProcessLifecycleEvent}，
 * 再推送到 AgentService → WebSocket 前端。
 * <p>
 * 去重机制：Subagent 在创建子 AgentProcess 时会继承父 process 的 listeners（见 Subagent.createProcessOptions），
 * 加上 Embabel 的事件冒泡机制，同一 Tool/LLM 事件会被子 process 和父 process 各触发一次。
 * 通过 correlationId（Tool 调用）或 action+model+timestamp 指纹（LLM/Action）进行去重。
 */
@Component
public class AgentProcessEventBridge implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessEventBridge.class);

    /** 延迟注入，避免与 defaultAgentPlatform 的循环依赖 */
    @org.springframework.beans.factory.annotation.Autowired @Lazy
    private AgentPlatform agentPlatform;

    /** 延迟注入场景服务，用于动态 action 白名单过滤 */
    @org.springframework.beans.factory.annotation.Autowired @Lazy
    private ScenarioService scenarioService;

    /** WorkspaceManager 用于从 sessionId 获取 workspaceId */
    @org.springframework.beans.factory.annotation.Autowired @Lazy
    private com.xinl.easyclaw.workspace.WorkspaceManager workspaceManager;

    private final Map<String, SessionListener> sessionListeners = new ConcurrentHashMap<>();
    private final Map<String, String> processToSession = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToProcess = new ConcurrentHashMap<>();
    private final Map<String, String> childToParent = new ConcurrentHashMap<>();
    /** processId → workspaceId 映射（用于场景白名单过滤） */
    private final Map<String, String> processToWorkspace = new ConcurrentHashMap<>();
    /** sessionId → workspaceId 映射 */
    private final Map<String, String> sessionToWorkspace = new ConcurrentHashMap<>();
    /** sessionId → 所有关联的 processId（含根和子进程） */
    private final Map<String, Set<String>> sessionProcessIds = new ConcurrentHashMap<>();
    /** 已清理的 sessionId（用于静默丢弃僵尸子进程事件，避免 WARN 刷屏） */
    private final Set<String> cleanedUpSessions = ConcurrentHashMap.newKeySet();

    private AgentProcessRepository processRepository;

    private AgentProcessRepository repo() {
        if (processRepository == null) {
            processRepository = agentPlatform.getPlatformServices().getAgentProcessRepository();
        }
        return processRepository;
    }

    /** processId → 已执行 Action 名称的有序列表 */
    private final Map<String, List<String>> processActionOrder = new ConcurrentHashMap<>();
    /** processId → 计划总步数 */
    private final Map<String, Integer> processTotalSteps = new ConcurrentHashMap<>();

    /** 去重：事件签名 → 第一次处理时间（毫秒） */
    private final Map<String, Long> eventDedupe = new ConcurrentHashMap<>();
    /** 去重窗口（毫秒）——超过此时间的签名会被清理 */
    private static final long DEDUP_WINDOW_MS = 60_000L;
    /** 上次清理时间 */
    private volatile long lastDedupeCleanup = System.currentTimeMillis();

    public void register(String sessionId, Consumer<ProcessLifecycleEvent> handler) {
        cleanedUpSessions.remove(sessionId);
        sessionListeners.put(sessionId, new SessionListener(sessionId, handler));
        log.debug("注册事件监听器: sessionId={}", sessionId);
    }

    public void unregister(String sessionId) {
        cleanedUpSessions.add(sessionId);
        sessionListeners.remove(sessionId);
        String processId = sessionToProcess.remove(sessionId);
        if (processId != null) {
            processToSession.remove(processId);
            processActionOrder.remove(processId);
            processTotalSteps.remove(processId);
        }
        Set<String> allProcessIds = sessionProcessIds.remove(sessionId);
        if (allProcessIds != null) {
            for (String pid : allProcessIds) {
                processToSession.remove(pid);
                processActionOrder.remove(pid);
                processTotalSteps.remove(pid);
            }
            childToParent.entrySet().removeIf(e -> allProcessIds.contains(e.getKey()) || allProcessIds.contains(e.getValue()));
        }
        log.debug("注销事件监听器: sessionId={}", sessionId);
    }

    public void bindProcess(String sessionId, String processId) {
        sessionToProcess.put(sessionId, processId);
        processToSession.put(processId, sessionId);
        sessionProcessIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(processId);
        processActionOrder.put(processId, new ArrayList<>());
        processTotalSteps.put(processId, 0);
        // 同步 workspaceId 映射
        String wsId = sessionToWorkspace.get(sessionId);
        if (wsId != null) {
            processToWorkspace.put(processId, wsId);
        }
        log.info("绑定 sessionId↔processId: session={}, process={}, workspace={}", sessionId, processId, wsId);
    }

    /** 绑定 sessionId → workspaceId 映射，用于场景白名单过滤 */
    public void bindWorkspace(String sessionId, String workspaceId) {
        sessionToWorkspace.put(sessionId, workspaceId);
        log.debug("绑定 sessionId↔workspaceId: session={}, workspace={}", sessionId, workspaceId);
    }

    /** 获取 session 下所有已注册的 processId（根进程 + 所有子进程，含 Repository 兜底查找） */
    public Set<String> getAllProcessIdsForSession(String sessionId) {
        Set<String> result = new LinkedHashSet<>();
        String root = sessionToProcess.get(sessionId);
        if (root != null) {
            result.add(root);
            // 用 Repository 递归查找所有后代，覆盖本地未跟踪的子进程
            collectAllDescendants(root, result);
        }
        Set<String> tracked = sessionProcessIds.get(sessionId);
        if (tracked != null) {
            result.addAll(tracked);
        }
        return result;
    }

    /** 通过 Embabel AgentProcessRepository 递归查找所有后代进程 */
    public void collectAllDescendants(String parentId, Set<String> out) {
        try {
            List<AgentProcess> children = repo().findByParentId(parentId);
            if (children == null || children.isEmpty()) return;
            for (AgentProcess child : children) {
                if (out.add(child.getId())) {
                    collectAllDescendants(child.getId(), out);
                }
            }
        } catch (Exception e) {
            log.warn("Repository 查找子进程失败: parentId={}, err={}", parentId, e.getMessage());
        }
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        try {
            // 进程创建事件：立即建立 parent-child 跟踪，确保 kill 时不遗漏
            if (event instanceof AgentProcessCreationEvent e) {
                handleProcessCreation(e);
                return;
            }

            String dedupKey = makeDedupeKey(event);
            if (dedupKey != null && checkDedupe(dedupKey)) {
                log.debug("去重跳过重复事件: key={}, eventType={}", dedupKey, event.getClass().getSimpleName());
                return;
            }

            if (event instanceof AgentProcessReadyToPlanEvent e) {
                handleReadyToPlan(e);
            } else if (event instanceof AgentProcessPlanFormulatedEvent e) {
                handlePlanFormulated(e);
            } else if (event instanceof ActionExecutionStartEvent e) {
                handleActionStart(e);
            } else if (event instanceof ActionExecutionResultEvent e) {
                handleActionResult(e);
            } else if (event instanceof ToolCallRequestEvent e) {
                handleToolCallRequest(e);
            } else if (event instanceof ToolCallResponseEvent e) {
                handleToolCallResponse(e);
            } else if (event instanceof LlmRequestEvent<?> e) {
                handleLlmRequest(e);
            } else if (event instanceof LlmResponseEvent<?> e) {
                handleLlmResponse(e);
            } else if (event instanceof LlmInvocationEvent e) {
                handleLlmInvocation(e);
            } else if (event instanceof ProgressUpdateEvent e) {
                handleProgressUpdate(e);
            } else if (event instanceof GoalAchievedEvent e) {
                handleGoalAchieved(e);
            } else if (event instanceof com.embabel.agent.api.event.ToolLoopStartEvent e) {
                handleToolLoopStart(e);
            } else if (event instanceof com.embabel.agent.api.event.ToolLoopCompletedEvent e) {
                handleToolLoopCompleted(e);
            } else if (event instanceof com.embabel.agent.api.event.StateTransitionEvent e) {
                handleStateTransition(e);
            } else if (event instanceof com.embabel.agent.api.event.ReplanRequestedEvent e) {
                handleReplanRequested(e);
            } else if (event instanceof AgentProcessPausedEvent e) {
                handlePaused(e.getAgentProcess());
            } else if (event instanceof AgentProcessWaitingEvent e) {
                handleWaiting(e.getAgentProcess());
            } else if (event instanceof com.embabel.agent.api.event.AgentProcessStuckEvent e) {
                handleStuck(e);
            } else if (event instanceof AgentProcessCompletedEvent e) {
                handleCompleted(e);
            } else if (event instanceof AgentProcessFailedEvent e) {
                handleFailed(e);
            }
        } catch (Exception ex) {
            log.error("处理 Embabel 事件异常: event={}, err={}", event.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    private void handleProcessCreation(AgentProcessCreationEvent event) {
        AgentProcess process = event.getAgentProcess();
        String childId = process.getId();
        String parentId = process.getParentId();
        if (parentId == null || parentId.isBlank()) {
            return;
        }
        childToParent.putIfAbsent(childId, parentId);
        resolveSessionId(process);
        log.info("子进程创建: child={}, parent={}, sessionId={}", childId, parentId, processToSession.get(childId));
    }

    private void handleReadyToPlan(AgentProcessReadyToPlanEvent event) {
        AgentProcess process = event.getAgentProcess();
        String sessionId = resolveSessionId(process);
        log.info("AgentProcess 就绪待规划: processId={}, parentId={}, sessionId={}",
                process.getId(), process.getParentId(), sessionId);
    }

    private String makeDedupeKey(AgentProcessEvent event) {
        if (event instanceof ToolCallRequestEvent e) {
            String cid = e.getCorrelationId();
            if (cid != null && !cid.isBlank()) return "TR:" + cid;
            // tool name 统一 toLowerCase：Embabel 里 FileTools.readFile 注册名可能是 readFile（驼峰），
            // 但实际 ToolCallRequest 里 tool 字段可能是 readfile（自动小写化），导致去重指纹不一致。
            String tool = e.getTool() != null ? e.getTool().toLowerCase() : "?";
            return "TR:" + e.getProcessId() + ":" + tool + ":" + (ts(e.getTimestamp()) / 100);
        }
        if (event instanceof ToolCallResponseEvent e) {
            ToolCallRequestEvent req = e.getRequest();
            String cid = req != null ? req.getCorrelationId() : null;
            if (cid != null && !cid.isBlank()) return "TE:" + cid;
            String tool = req != null && req.getTool() != null ? req.getTool().toLowerCase() : "?";
            return "TE:" + (req != null ? req.getProcessId() : e.getProcessId()) + ":"
                    + tool + ":" + (ts(e.getTimestamp()) / 100);
        }
        if (event instanceof LlmRequestEvent<?> e) {
            String actionName = e.getAction() != null ? e.getAction().shortName() : "?";
            String model = e.getLlmMetadata() != null ? e.getLlmMetadata().getName() : "?";
            return "LR:" + e.getProcessId() + ":" + shortenName(actionName) + ":" + model + ":" + (ts(e.getTimestamp()) / 100);
        }
        if (event instanceof LlmResponseEvent<?> e) {
            LlmRequestEvent<?> req = e.getRequest();
            String actionName = req != null && req.getAction() != null ? req.getAction().shortName() : "?";
            String model = req != null && req.getLlmMetadata() != null ? req.getLlmMetadata().getName() : "?";
            return "LE:" + (req != null ? req.getProcessId() : e.getProcessId()) + ":"
                    + shortenName(actionName) + ":" + model + ":" + (ts(e.getTimestamp()) / 100);
        }
        if (event instanceof ActionExecutionStartEvent e) {
            String actionName = e.getAction() != null ? e.getAction().shortName() : "?";
            return "AS:" + e.getProcessId() + ":" + shortenName(actionName) + ":" + (ts(e.getTimestamp()) / 100);
        }
        if (event instanceof ActionExecutionResultEvent e) {
            String actionName = e.getAction() != null ? e.getAction().shortName() : "?";
            return "AE:" + e.getProcessId() + ":" + shortenName(actionName) + ":" + (ts(e.getTimestamp()) / 100);
        }
        return null;
    }

    private static long ts(Instant instant) {
        return instant != null ? instant.toEpochMilli() : System.currentTimeMillis();
    }

    private boolean checkDedupe(String key) {
        long now = System.currentTimeMillis();
        cleanupIfNeeded(now);
        Long prev = eventDedupe.putIfAbsent(key, now);
        return prev != null;
    }

    private void cleanupIfNeeded(long now) {
        if (now - lastDedupeCleanup < DEDUP_WINDOW_MS) return;
        lastDedupeCleanup = now;
        eventDedupe.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
    }

    private void handleToolCallRequest(ToolCallRequestEvent event) {
        AgentProcess process = event.getAgentProcess();
        com.embabel.agent.core.Action action = event.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("tool", event.getTool());
        data.put("input", truncate(event.getToolInput(), 2000));
        data.put("correlationId", event.getCorrelationId());
        data.put("status", "running");

        log.debug("ToolCall 请求: processId={}, action={}, tool={}", process.getId(), actionName, event.getTool());

        publish(process, ProcessLifecycleEvent.Type.TOOL_CALL_START, data);
    }

    private void handleToolCallResponse(ToolCallResponseEvent event) {
        ToolCallRequestEvent req = event.getRequest();
        AgentProcess process = req.getAgentProcess();
        com.embabel.agent.core.Action action = req.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";
        Object result = invokeMangledGetter(event, "getResult-d1pmJ48");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("tool", req.getTool());
        data.put("input", truncate(req.getToolInput(), 2000));
        data.put("output", truncate(result != null ? result.toString() : "null", 3000));
        data.put("correlationId", req.getCorrelationId());
        data.put("durationMs", event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0L);
        data.put("status", "done");

        log.debug("ToolCall 响应: processId={}, tool={}, cost={}ms", process.getId(), req.getTool(),
                event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0);

        publish(process, ProcessLifecycleEvent.Type.TOOL_CALL_END, data);
    }

    private void handleLlmRequest(LlmRequestEvent<?> event) {
        AgentProcess process = event.getAgentProcess();
        com.embabel.agent.core.Action action = event.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";

        StringBuilder promptPreview = new StringBuilder();
        if (event.getMessages() != null) {
            int count = 0;
            for (com.embabel.chat.Message msg : event.getMessages()) {
                if (count++ >= 8) break;
                String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "?";
                String content = truncate(msg.getContent(), 300);
                promptPreview.append("[").append(role).append("] ").append(content).append("\n");
            }
            if (event.getMessages().size() > 8) {
                promptPreview.append("... (共 ").append(event.getMessages().size()).append(" 条消息)\n");
            }
        }

        String modelName = event.getLlmMetadata() != null ? event.getLlmMetadata().getName() : "";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("model", modelName);
        data.put("promptPreview", promptPreview.toString().trim());
        data.put("messageCount", event.getMessages() != null ? event.getMessages().size() : 0);
        data.put("status", "running");

        log.debug("LLM 请求: processId={}, action={}, model={}, msgs={}",
                process.getId(), actionName, modelName, data.get("messageCount"));

        publish(process, ProcessLifecycleEvent.Type.LLM_CALL_START, data);
    }

    private void handleLlmResponse(LlmResponseEvent<?> event) {
        LlmRequestEvent<?> req = event.getRequest();
        AgentProcess process = req.getAgentProcess();
        com.embabel.agent.core.Action action = req.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";

        Object response = event.getResponse();

        // ---- 提取 LLM Thinking/Reasoning 内容 ----
        tryExtractThinking(process, response);

        String responseText;
        if (response instanceof com.embabel.common.core.thinking.ThinkingResponse<?> tr) {
            Object result = tr.getResult();
            responseText = result != null ? result.toString() : "";
        } else {
            responseText = response != null ? response.toString() : "null";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("model", req.getLlmMetadata() != null ? req.getLlmMetadata().getName() : "");
        data.put("responsePreview", truncate(responseText, 2000));
        data.put("responseLength", responseText.length());
        data.put("durationMs", event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0L);
        data.put("status", "done");

        log.debug("LLM 响应: processId={}, model={}, cost={}ms, respLen={}",
                process.getId(), data.get("model"),
                event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0,
                responseText.length());

        publish(process, ProcessLifecycleEvent.Type.LLM_CALL_END, data);
    }

    /** 从 LlmResponse 中提取 Thinking 内容，发布为 LLM_THINKING 事件 */
    private void tryExtractThinking(AgentProcess process, Object response) {
        if (!(response instanceof com.embabel.common.core.thinking.ThinkingResponse<?> tr)) {
            return;
        }
        String thinkingContent = tr.getThinkingContent();
        if (thinkingContent == null || thinkingContent.isBlank()) {
            return;
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        try {
            for (var block : tr.getThinkingBlocks()) {
                Map<String, Object> blockData = new LinkedHashMap<>();
                blockData.put("type", block.getTagType().name());
                blockData.put("tagValue", block.getTagValue());
                blockData.put("content", block.getContent());
                blocks.add(blockData);
            }
        } catch (Exception e) {
            log.debug("提取 ThinkingBlocks 失败: {}", e.getMessage());
        }

        Map<String, Object> thinkingData = new LinkedHashMap<>();
        thinkingData.put("thinkingContent", truncate(thinkingContent, 5000));
        thinkingData.put("thinkingBlocks", blocks);
        thinkingData.put("hasThinking", tr.hasThinking());

        log.info("LLM Thinking 内容: processId={}, thinkingLen={}, blocks={}",
                process.getId(), thinkingContent.length(), blocks.size());

        publish(process, ProcessLifecycleEvent.Type.LLM_THINKING, thinkingData);
    }

    /** LLM 调用元数据（模型、交互 ID） */
    private void handleLlmInvocation(LlmInvocationEvent event) {
        AgentProcess process = event.getAgentProcess();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interactionId", event.getInteractionId());
        if (event.getInvocation() != null) {
            data.put("modelUsed", event.getInvocation().getLlmMetadata() != null
                    ? event.getInvocation().getLlmMetadata().getName() : "");
        }
        log.debug("LLM Invocation: processId={}, interactionId={}", process.getId(), event.getInteractionId());
        publish(process, ProcessLifecycleEvent.Type.LLM_INVOCATION, data);
    }

    /** 进度更新 */
    private void handleProgressUpdate(ProgressUpdateEvent event) {
        AgentProcess process = event.getAgentProcess();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", event.getName());
        data.put("current", event.getCurrent());
        data.put("total", event.getTotal());
        log.debug("Progress Update: processId={}, {} {}/{}", process.getId(), event.getName(), event.getCurrent(), event.getTotal());
        publish(process, ProcessLifecycleEvent.Type.PROGRESS_UPDATE, data);
    }

    /** 目标达成 */
    private void handleGoalAchieved(GoalAchievedEvent event) {
        AgentProcess process = event.getAgentProcess();
        Map<String, Object> data = new LinkedHashMap<>();
        if (event.getGoal() != null) {
            data.put("goal", event.getGoal().getName());
        }
        if (event.getWorldState() != null) {
            data.put("worldState", event.getWorldState().toString());
        }
        log.info("Goal Achieved: processId={}, goal={}", process.getId(), data.get("goal"));
        publish(process, ProcessLifecycleEvent.Type.GOAL_ACHIEVED, data);
    }

    /** 工具循环开始 */
    private void handleToolLoopStart(com.embabel.agent.api.event.ToolLoopStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        String actionName = event.getAction() != null ? shortenName(event.getAction().shortName()) : "?";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("toolNames", event.getToolNames());
        data.put("maxIterations", event.getMaxIterations());
        data.put("interactionId", event.getInteractionId());
        log.debug("ToolLoop Start: processId={}, action={}, tools={}", process.getId(), actionName, event.getToolNames());
        publish(process, ProcessLifecycleEvent.Type.TOOL_LOOP_START, data);
    }

    /** 工具循环完成 */
    private void handleToolLoopCompleted(com.embabel.agent.api.event.ToolLoopCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String actionName = event.getAction() != null ? shortenName(event.getAction().shortName()) : "?";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", actionName);
        data.put("totalIterations", event.getTotalIterations());
        data.put("replanRequested", event.getReplanRequested());
        data.put("durationMs", event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0L);
        log.debug("ToolLoop Completed: processId={}, action={}, iterations={}", process.getId(), actionName, event.getTotalIterations());
        publish(process, ProcessLifecycleEvent.Type.TOOL_LOOP_COMPLETED, data);
    }

    /** 状态转换 */
    private void handleStateTransition(com.embabel.agent.api.event.StateTransitionEvent event) {
        AgentProcess process = event.getAgentProcess();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("previousState", event.getPreviousState() != null ? event.getPreviousState().toString() : "");
        data.put("newState", event.getNewState() != null ? event.getNewState().toString() : "");
        data.put("initial", event.isInitialState());
        log.debug("State Transition: processId={}, {} → {}", process.getId(), data.get("previousState"), data.get("newState"));
        publish(process, ProcessLifecycleEvent.Type.STATE_TRANSITION, data);
    }

    /** 重新规划请求 */
    private void handleReplanRequested(com.embabel.agent.api.event.ReplanRequestedEvent event) {
        AgentProcess process = event.getAgentProcess();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", event.getReason());
        log.info("Replan Requested: processId={}, reason={}", process.getId(), event.getReason());
        publish(process, ProcessLifecycleEvent.Type.REPLAN_REQUESTED, data);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }

    /** 根据子/父 agent 名推断分类，供前端按场景/子智能体归类工具输出 */
    private static String inferAgentCategory(String agentName, String parentAgentName) {
        if (agentName == null) agentName = "";
        String lower = agentName.toLowerCase();

        if (lower.contains("coding") || lower.contains("code")) return "代码";
        if (lower.contains("file")) return "文件";
        if (lower.contains("verify") || lower.contains("verifier")) return "验证";
        if (lower.contains("interaction")) return "交互";
        if (lower.contains("mail")) return "邮件";
        if (lower.contains("content")) return "内容";
        if (lower.contains("data")) return "数据";
        if (lower.contains("web")) return "网页";
        if (lower.contains("devops")) return "运维";
        if (lower.contains("research")) return "调研";

        if (parentAgentName != null) {
            String parentLower = parentAgentName.toLowerCase();
            if (parentLower.contains("coding")) return "代码";
            if (parentLower.contains("weekly")) return "周报";
            if (parentLower.contains("content-create")) return "内容创作";
            if (parentLower.contains("mail-triage")) return "邮件分拣";
            if (parentLower.contains("data-analysis")) return "数据分析";
            if (parentLower.contains("devops")) return "运维";
        }
        return "默认";
    }

    private void handlePlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        Plan plan = event.getPlan();

        List<Map<String, Object>> steps = new ArrayList<>();
        int total = 0;
        if (plan != null && plan.getActions() != null) {
            for (com.embabel.plan.Action action : plan.getActions()) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("name", shortenName(action.getName()));
                step.put("description", "");

                Map<String, String> preconds = new LinkedHashMap<>();
                Map<String, String> effects = new LinkedHashMap<>();
                if (action instanceof com.embabel.plan.common.condition.ConditionAction ca) {
                    if (ca.getPreconditions() != null) {
                        ca.getPreconditions().forEach((k, v) -> preconds.put(k, v != null ? v.name() : ""));
                    }
                    if (ca.getEffects() != null) {
                        ca.getEffects().forEach((k, v) -> effects.put(k, v != null ? v.name() : ""));
                    }
                }
                step.put("preconditions", preconds);
                step.put("effects", effects);
                steps.add(step);
            }
            total = plan.getActions().size();
        }

        processTotalSteps.put(process.getId(), total);
        processActionOrder.put(process.getId(), new ArrayList<>());

        String goalName = plan != null && plan.getGoal() != null ? shortenName(plan.getGoal().getName()) : "";
        String goalDesc = "";
        Map<String, String> goalPreconds = new LinkedHashMap<>();
        Set<String> goalKnownConds = new LinkedHashSet<>();
        if (plan != null && plan.getGoal() instanceof com.embabel.agent.core.Goal coreGoal) {
            goalDesc = coreGoal.getDescription() != null ? coreGoal.getDescription() : "";
        }
        if (plan != null && plan.getGoal() instanceof com.embabel.plan.common.condition.ConditionGoal cg) {
            if (cg.getPreconditions() != null) {
                cg.getPreconditions().forEach((k, v) -> goalPreconds.put(k, v != null ? v.name() : ""));
            }
            if (cg.getKnownConditions() != null) {
                goalKnownConds.addAll(cg.getKnownConditions());
            }
        }

        Map<String, Object> planData = new LinkedHashMap<>();
        planData.put("goal", goalName);
        planData.put("goalDescription", goalDesc);
        planData.put("goalPreconditions", goalPreconds);
        planData.put("goalKnownConditions", new ArrayList<>(goalKnownConds));
        planData.put("totalSteps", total);
        planData.put("steps", steps);

        com.embabel.agent.core.Agent agent = process.getAgent();
        if (agent != null) {
            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("name", agent.getName());
            agentInfo.put("description", agent.getDescription() != null ? agent.getDescription() : "");

            List<Map<String, String>> agentActions = new ArrayList<>();
            if (agent.getActions() != null) {
                for (com.embabel.agent.core.Action a : agent.getActions()) {
                    Map<String, String> ai = new LinkedHashMap<>();
                    ai.put("name", a.getName() != null ? shortenName(a.getName()) : a.shortName());
                    String desc = a.getDescription();
                    ai.put("description", desc != null ? desc : "");
                    agentActions.add(ai);
                }
            }
            agentInfo.put("actions", agentActions);

            List<String> agentGoals = new ArrayList<>();
            if (agent.getGoals() != null) {
                for (com.embabel.agent.core.Goal g : agent.getGoals()) {
                    agentGoals.add(g.getName());
                }
            }
            agentInfo.put("goals", agentGoals);

            planData.put("agent", agentInfo);
        }

        log.info("AgentProcess 计划制定: processId={}, goal={}, preconds={}, totalSteps={}, steps={}",
                process.getId(), goalName, goalPreconds, total,
                steps.stream().map(s -> s.get("name")).toList());

        publish(process, ProcessLifecycleEvent.Type.PLAN_FORMULATED, planData);
        log.info("AgentProcess 计划已发布，等待 action 执行: processId={}", process.getId());
    }

    private void handleActionStart(ActionExecutionStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        com.embabel.agent.core.Action action = event.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";
        String description = "";
        if (action != null) {
            try {
                description = new ActionMetadata(action).getDescription();
            } catch (Exception ignored) {}
        }

        List<String> order = processActionOrder.computeIfAbsent(process.getId(), k -> new ArrayList<>());
        int index = order.size();
        order.add(actionName);

        int total = processTotalSteps.getOrDefault(process.getId(), 0);

        Map<String, Object> stepData = new LinkedHashMap<>();
        stepData.put("name", actionName);
        stepData.put("description", description != null ? description : "");
        stepData.put("status", "running");
        stepData.put("index", index);
        stepData.put("total", total);

        log.info("AgentProcess action 开始: processId={}, action={}, step={}/{}",
                process.getId(), actionName, index + 1, total);

        publish(process, ProcessLifecycleEvent.Type.ACTION_START, stepData);
    }

    private void handleActionResult(ActionExecutionResultEvent event) {
        AgentProcess process = event.getAgentProcess();
        com.embabel.agent.core.Action action = event.getAction();
        String actionName = action != null ? shortenName(action.shortName()) : "?";
        String description = "";
        if (action != null) {
            try {
                description = new ActionMetadata(action).getDescription();
            } catch (Exception ignored) {}
        }
        String status = event.getActionStatus() != null && event.getActionStatus().getStatus() != null
                ? event.getActionStatus().getStatus().name() : "?";

        List<String> order = processActionOrder.getOrDefault(process.getId(), List.of());
        int index = Math.max(0, order.indexOf(actionName));
        int total = processTotalSteps.getOrDefault(process.getId(), 0);

        String uiStatus = switch (status) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED" -> "done";
            case "FAILED" -> "failed";
            default -> "done";
        };

        Map<String, Object> stepData = new LinkedHashMap<>();
        stepData.put("name", actionName);
        stepData.put("description", description != null ? description : "");
        stepData.put("status", uiStatus);
        stepData.put("index", index);
        stepData.put("total", total);
        stepData.put("durationMs", event.getRunningTime() != null ? event.getRunningTime().toMillis() : 0L);

        log.info("AgentProcess action 结束: processId={}, action={}, status={}, step={}/{}",
                process.getId(), actionName, uiStatus, index + 1, total);

        publish(process, ProcessLifecycleEvent.Type.ACTION_END, stepData);
    }

    private static String shortenName(String fqn) {
        if (fqn == null || fqn.isBlank()) return "";
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) return fqn;
        return fqn.substring(lastDot + 1);
    }

    private static Object invokeMangledGetter(Object target, String mangledName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(mangledName);
            return m.invoke(target);
        } catch (Exception e) {
            log.debug("反射调用 {} 失败: {}", mangledName, e.getMessage());
            return null;
        }
    }

    private void handlePaused(AgentProcess process) {
        log.info("AgentProcess 暂停: processId={}", process.getId());
        publish(process, ProcessLifecycleEvent.Type.PAUSED, null);
    }

    private void handleWaiting(AgentProcess process) {
        log.info("AgentProcess 等待输入: processId={}", process.getId());
        publish(process, ProcessLifecycleEvent.Type.WAITING, null);
    }

    private void handleCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        Object result = null;
        try {
            result = event.getResult();
        } catch (Exception e) {
            log.debug("AgentProcessCompletedEvent.getResult() 抛异常（可能 lastResult 为空），改用 process.resultOfType", e);
        }
        log.info("AgentProcess 完成: processId={}, cost={}, resultType={}",
                process.getId(), process.totalCost(),
                result != null ? result.getClass().getSimpleName() : "null");

        Map<String, Object> completeData = new LinkedHashMap<>();
        completeData.put("cost", process.totalCost());
        completeData.put("totalSteps", processTotalSteps.getOrDefault(process.getId(), 0));

        publish(process, ProcessLifecycleEvent.Type.COMPLETED, completeData);
        cleanupProcess(process.getId());
    }

    private void handleFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.error("AgentProcess 失败: processId={}, error={}", process.getId(), process.getFailureInfo());
        publish(process, ProcessLifecycleEvent.Type.FAILED, process.getFailureInfo());
        cleanupProcess(process.getId());
    }

    private void handleStuck(com.embabel.agent.api.event.AgentProcessStuckEvent event) {
        AgentProcess process = event.getAgentProcess();
        String processId = process.getId();
        long elapsedSec = process.getRunningTime().toSeconds();
        String msg = String.format("进程 %s 执行卡住（运行 %ds），已由框架自动终止。可尝试简化任务或检查工具调用是否阻塞。",
                processId, elapsedSec);
        log.warn("AgentProcess 卡住: processId={}, elapsed={}s, parentId={}",
                processId, elapsedSec, process.getParentId());
        publish(process, ProcessLifecycleEvent.Type.STUCK, msg);
        cleanupProcess(processId);
    }

    private void cleanupProcess(String processId) {
        processActionOrder.remove(processId);
        processTotalSteps.remove(processId);
    }

    private void publish(AgentProcess process, ProcessLifecycleEvent.Type type, Object data) {
        String processId = process.getId();
        String sessionId = resolveSessionId(process);

        // 会话已清理 → 僵尸子进程事件静默丢弃（避免 WARN 刷屏）
        if (sessionId != null && cleanedUpSessions.contains(sessionId)) {
            log.debug("僵尸进程事件已丢弃: processId={}, sessionId={}, type={}", processId, sessionId, type);
            return;
        }

        String agentName = null;
        String parentAgentName = null;
        try {
            com.embabel.agent.core.Agent agent = process.getAgent();
            if (agent != null) agentName = agent.getName();
        } catch (Exception ignored) {}
        if (agentName == null || agentName.isBlank()) {
            agentName = type.toString().toLowerCase();
        }

        Object finalData = data;
        if (data instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) rawMap);
            copy.put("processId", processId);
            copy.put("agentName", agentName);

            String parentId = process.getParentId();
            if (parentId == null || parentId.isBlank()) {
                parentId = childToParent.get(processId);
            }
            if (parentId != null && !parentId.isBlank()) {
                copy.put("parentProcessId", parentId);
                // 查找父进程的 agent 名字，便于前端按场景归类
                try {
                    AgentProcess parentProc = repo().findById(parentId);
                    if (parentProc != null && parentProc.getAgent() != null) {
                        parentAgentName = parentProc.getAgent().getName();
                        copy.put("parentAgentName", parentAgentName);
                    }
                } catch (Exception ignored) {}
            }

            // 按 agent 名推断分类（前端可直接按 category 分组工具输出）
            copy.put("agentCategory", inferAgentCategory(agentName, parentAgentName));

            finalData = copy;
        }

        ProcessLifecycleEvent evt = new ProcessLifecycleEvent(processId, type, finalData);

        log.info("EVENT BRIDGE: type={}, processId={}, sessionId={}, agentName={}", type, processId, sessionId, agentName);

        if (sessionId != null) {
            SessionListener listener = sessionListeners.get(sessionId);
            if (listener != null) {
                try {
                    listener.handler.accept(evt);
                } catch (Exception e) {
                    log.error("推送生命周期事件失败: sessionId={}, err={}", sessionId, e.getMessage());
                }
                return;
            }
            // session 有映射但 handler 已移除 → 说明是僵尸进程，静默丢弃
            log.debug("sessionId={} 已注销，丢弃僵尸事件 type={}", sessionId, type);
        } else {
            log.warn("processId={} (parent={}) 无 sessionId 映射，丢弃事件 type={}",
                    processId, process.getParentId(), type);
        }
    }

    private String resolveSessionId(AgentProcess process) {
        String sessionId = processToSession.get(process.getId());
        if (sessionId != null) return sessionId;

        String childId = process.getId();
        String parentId = process.getParentId();
        if (parentId != null) {
            childToParent.putIfAbsent(childId, parentId);
        }

        // 先沿本地缓存的 parent 链回溯
        String walkParent = parentId;
        while (walkParent != null) {
            sessionId = processToSession.get(walkParent);
            if (sessionId != null) {
                sessionProcessIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(childId);
                break;
            }
            walkParent = childToParent.get(walkParent);
        }

        // 本地缓存找不到 → 用 Embabel ProcessRepository 兜底（处理父进程由其他方式创建的情况）
        if (sessionId == null && parentId != null) {
            sessionId = resolveSessionViaRepository(parentId, childId);
        }

        if (sessionId != null) {
            processToSession.put(childId, sessionId);
        }
        return sessionId;
    }

    /** 通过 Embabel ProcessRepository 逐级向上查找父进程的 sessionId */
    private String resolveSessionViaRepository(String startParentId, String childId) {
        String current = startParentId;
        Set<String> visited = new HashSet<>();
        int depth = 0;
        while (current != null && visited.add(current) && depth < 20) {
            String sessionId = processToSession.get(current);
            if (sessionId != null) {
                sessionProcessIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(childId);
                return sessionId;
            }
            AgentProcess parentProc = repo().findById(current);
            if (parentProc == null) break;
            String nextParent = parentProc.getParentId();
            if (nextParent != null) {
                childToParent.putIfAbsent(current, nextParent);
            }
            current = nextParent;
            depth++;
        }
        return null;
    }

    private record SessionListener(String sessionId, Consumer<ProcessLifecycleEvent> handler) {}
}
