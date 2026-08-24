package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.ChatResponse;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.RetryableHttpTransport;
import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.harness.agent.HarnessAgent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent 服务门面
 * <p>
 * 封装 AgentScope HarnessAgent 调用逻辑，为 UI 层提供对话接口。
 * 支持流式输出（推理/工具/子 Agent）、工具执行追溯（参数/结果），
 * 以及工具执行前的用户确认（写文件/编辑/shell 等安全拦截）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WorkspaceManager workspaceManager;
    private final AgentFactory agentFactory;
    private final PermissionRuleService permissionRuleService;
    /** 待用户确认的工具调用（按会话隔离） */
    private final Map<String, List<ToolUseBlock>> pendingConfirms = new ConcurrentHashMap<>();
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
    /** 是否处于「确认后恢复」阶段：原暂停流结束时抑制其 end，由恢复流统一发送，避免重复/提前结束 UI */
    private final Map<String, AtomicBoolean> resuming = new ConcurrentHashMap<>();
    /** 工具连续失败计数（sessionId → toolName → 次数），用于护栏 */
    private final Map<String, Map<String, Integer>> toolFailCounts = new ConcurrentHashMap<>();

    public AgentService(WorkspaceManager workspaceManager,
                        AgentFactory agentFactory,
                        PermissionRuleService permissionRuleService) {
        this.workspaceManager = workspaceManager;
        this.agentFactory = agentFactory;
        this.permissionRuleService = permissionRuleService;
    }

    /**
     * 本回合允许指定工具（不再弹窗确认）
     */
    public void allowTurn(String sessionId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        Set<String> set = turnAllowed.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        set.addAll(toolNames);
        log.info("本回合已允许工具: sessionId={}, tools={}", sessionId, toolNames);
    }

    /**
     * 永久允许指定工具（持久化到 DB，绑定 Workspace + 立即生效）
     * <p>
     * 注意：这里不能 rebuildAgent —— 会 close 掉可能正暂停等待确认（ASKING）的 Agent，
     * 丢失内存中的挂起确认状态，导致恢复消息走普通路径写入模型上下文，进而引发确认循环。
     * 新会话由构建时的 buildPermissionContext 从 DB 注入规则，存量会话靠这里同步。
     */
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
        syncRulesToLiveSessions(workspaceId);
    }

    /**
     * 撤销指定 Workspace 的永久授权（删除后立即生效）
     */
    public void revokePermanently(String workspaceId, String toolName) {
        permissionRuleService.remove(workspaceId, toolName);
        log.info("已撤销永久授权: workspaceId={}, tool={}", workspaceId, toolName);
        syncRulesToLiveSessions(workspaceId);
    }

    /**
     * 把权限规则同步到该 Workspace 当前存活 Agent 的所有活跃会话
     */
    private void syncRulesToLiveSessions(String workspaceId) {
        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null || workspace.getAgent() == null) {
            return;
        }
        for (Map.Entry<String, String> e : sessionWorkspaces.entrySet()) {
            if (workspaceId.equals(e.getValue())) {
                syncPermissionRules(workspace.getAgent(), workspace, e.getKey(), workspaceId);
            }
        }
    }

    /**
     * 把权限规则同步进指定会话的 PermissionContextState（立即生效 + 随 AgentState 持久化）。
     * <p>
     * AgentScope 2.0.2 权限语义（ReActAgent$CallExecution 实测）：
     * <ul>
     *   <li>ALLOW 规则：工具静默执行 —— 不发 REQUIRE_USER_CONFIRM，不注入任何消息，LLM 完全无感</li>
     *   <li>DENY 规则 / ConfirmResult(false)：框架回 ToolResultBlock("Permission denied by user") 给 LLM</li>
     *   <li>无规则匹配：ASK —— 发 RequireUserConfirmEvent 暂停等待用户确认</li>
     * </ul>
     * 用户来源（source="user"）的 ALLOW 规则以 DB 永久规则 + 回合授权为准（支持撤销），
     * system 来源的规则（只读放行/写类 ASK）原样保留。
     */
    private void syncPermissionRules(HarnessAgent agent, WorkspaceContext workspace,
                                     String sessionId, String workspaceId) {
        try {
            String userId = workspace.getUserId() == null
                    ? AppConstants.DEFAULT_USER_ID : workspace.getUserId();
            ReActAgent core = agent.getDelegate();
            AgentState state = core.getAgentState(userId, sessionId);
            PermissionContextState existing = state.getPermissionContext();

            // 目标用户级 ALLOW 集合：DB 永久规则 ∪ 本回合授权
            Set<String> allowed = new HashSet<>(permissionRuleService.alwaysAllowedTools(workspaceId));
            Set<String> turn = turnAllowed.get(sessionId);
            if (turn != null) {
                allowed.addAll(turn);
            }

            // 无变化则跳过（replacePermissionContext 会触发状态落盘）
            Set<String> existingUserTools = new HashSet<>();
            if (existing != null) {
                existing.getAllowRules().values().forEach(list -> list.stream()
                        .filter(r -> "user".equals(r.source()))
                        .map(PermissionRule::toolName)
                        .forEach(existingUserTools::add));
            }
            // 判定顺序 deny → ask → allow：ASK 规则优先于 ALLOW 命中。
            // 已授权工具若仍挂着 ASK 规则（旧状态的坏组合），ALLOW 永远不生效 —— 必须重建
            boolean askClean = existing == null || existing.getAskRules().values().stream()
                    .flatMap(List::stream)
                    .map(PermissionRule::toolName)
                    .noneMatch(allowed::contains);
            if (existingUserTools.equals(allowed) && askClean) {
                return;
            }

            PermissionContextState.Builder b = PermissionContextState.builder();
            if (existing != null) {
                if (existing.getMode() != null) {
                    b.mode(existing.getMode());
                }
                copySystemRules(existing.getAllowRules(), b::addAllowRule, Set.of());
                copySystemRules(existing.getDenyRules(), b::addDenyRule, Set.of());
                // 关键：已授权工具的 system ASK 规则【不复制】——
                // ASK 优先于 ALLOW，留着它用户授权就永远不生效（反复弹窗的根因）
                copySystemRules(existing.getAskRules(), b::addAskRule, allowed);
            }
            for (String tool : allowed) {
                b.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "user"));
            }
            core.replacePermissionContext(userId, sessionId, b.build());
            log.debug("已同步工具权限规则: session={}, userAllowed={}", sessionId, allowed);
        } catch (Exception e) {
            log.warn("同步权限规则失败（回退确认弹窗模式）: session={}, err={}", sessionId, e.getMessage());
        }
    }

    /** 复制非用户来源的规则（system 只读放行 / 写类 ASK 等），用户来源规则以最新授权为准；
     *  exclude 中的工具不复制（用于摘掉已授权工具的 system ASK 规则） */
    private void copySystemRules(Map<String, List<PermissionRule>> rules,
                                 java.util.function.BiConsumer<String, PermissionRule> adder,
                                 Set<String> exclude) {
        rules.forEach((name, list) -> list.stream()
                .filter(r -> !"user".equals(r.source()))
                .filter(r -> !exclude.contains(r.toolName()))
                .forEach(r -> adder.accept(name, r)));
    }

    /**
     * 该 Workspace 的永久授权规则列表（UI 展示用）
     */
    public List<PermissionRuleEntity> permanentRules(String workspaceId) {
        return permissionRuleService.findForWorkspace(workspaceId);
    }

    /**
     * 查询当前挂起的工具确认（前端轮询兜底：SSE confirm 事件丢失时也能弹窗）
     */
    public List<Map<String, Object>> pendingConfirmInfo(String sessionId) {
        List<ToolUseBlock> tools = pendingConfirms.get(sessionId);
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("input", t.getInput() != null ? t.getInput().toString() : "{}");
            return m;
        }).toList();
    }

    /**
     * 强制停止指定会话的流输出：
     * <ol>
     *   <li>dispose Flux 订阅（立即取消 HTTP 流接收，不再推事件）</li>
     *   <li>调用 agent.interrupt(userId, sessionId)（按会话槽精确设置中断标志，
     *       让 Agent 循环提前退出并释放 AgentBase 的 callGates 串行化闸门）</li>
     *   <li>清理挂起确认（防止残留 ASKING 状态导致下轮对话异常）</li>
     * </ol>
     */
    public void stopChat(String workspaceId, String sessionId) {
        log.info("停止会话流: workspaceId={}, sessionId={}", workspaceId, sessionId);

        // 1. 立即中止所有 HTTP 重试（让正在等待退避的 retry 下次判断时直接放弃）
        RetryableHttpTransport.abortAll();

        // 2. 取消 Flux 订阅
        Disposable disp = sessionDisposables.remove(sessionId);
        if (disp != null && !disp.isDisposed()) {
            try {
                disp.dispose();
                log.info("已 dispose Flux 订阅: sessionId={}", sessionId);
            } catch (Exception e) {
                log.warn("dispose 订阅异常: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }

        // 3. 中断 AgentScope 执行
        //
        // 【必须按 (userId, sessionId) 精确中断】
        // AgentScope 的中断标志挂在「按 slotKey(userId, sessionId) 划分的 AgentState」上，
        // 而 HarnessAgent.interrupt() / ReActAgent.interrupt() 无参版本等价于
        // interrupt(null, defaultSessionId) —— 打到的是默认槽，命中不了本会话的槽，
        // 于是 Agent 内部循环不会提前退出。
        // 更致命的是 AgentBase 用 callGates(key=slotKey) 对同一会话的执行做串行化
        // (serializeOnKey)，gate 只在上一次执行 doFinally 时释放；旧执行没被真正中断
        // → gate 一直被占 → 下一条消息在 gate 上无声排队，表现为「后端卡住、不发任何事件」。
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws != null && ws.getAgent() != null) {
            String userId = ws.getUserId() == null ? AppConstants.DEFAULT_USER_ID : ws.getUserId();
            try {
                ws.getAgent().getDelegate().interrupt(userId, sessionId);
                log.info("已调用 agent.interrupt(userId={}, sessionId={}): workspaceId={}",
                        userId, sessionId, workspaceId);
            } catch (Exception e) {
                log.warn("agent.interrupt(userId, sessionId) 异常: workspaceId={}, err={}", workspaceId, e.getMessage());
                // 兜底：退回无参版本，至少中断默认槽
                try {
                    ws.getAgent().interrupt();
                } catch (Exception ignore) {
                    log.warn("兜底 agent.interrupt() 也失败: {}", ignore.getMessage());
                }
            }
        }

        pendingConfirms.remove(sessionId);
        turnAllowed.remove(sessionId);
        resuming.remove(sessionId);
        toolFailCounts.remove(sessionId);
    }

    /**
     * 查询指定会话的运行状态（重连/刷新后前端用来恢复 UI 状态）。
     *
     * @return Map 包含 running(是否有活跃流)、pending(是否挂起等待确认)、pendingTools(待确认工具列表)
     */
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

    /**
     * 使用指定 Workspace 的 Agent 进行流式对话（异步，纯文本）
     */
    public void streamChat(String workspaceId, String sessionId, String message,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, List.of(),
                null,
                onEvent, onError, onFinish);
    }

    /**
     * 使用指定 Workspace 的 Agent 进行流式对话（异步，支持截图/附件）。
     */
    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        streamChat(workspaceId, sessionId, message, attachments,
                null,
                onEvent, onError, onFinish);
    }

    /**
     * 流式对话入口：Skill 通过 system prompt 注入（不重建 Agent）。
     * <p>Plan Mode 始终保持框架默认开启，按会话隔离 plan 文件路径。</p>
     *
     * @param skillName 注入的 Skill 名称，null 表示不注入
     */
    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           String skillName,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        // 空消息防护（兜底，WS 入口已拦截）：文本为空且无有效附件时直接拒绝，
        // 避免空 user 消息进入模型上下文，引发"你发了一条空消息"式回复与上下文污染
        boolean hasText = message != null && !message.isBlank();
        boolean hasAttachment = attachments != null && attachments.stream()
                .anyMatch(a -> a != null && a.base64Data() != null && !a.base64Data().isBlank());
        if (!hasText && !hasAttachment) {
            log.warn("拒绝空消息: workspaceId={}, sessionId={}", workspaceId, sessionId);
            onError.accept(new RuntimeException("消息内容为空：请输入文字或添加附件后再发送。"));
            onFinish.run();
            return;
        }

        // 重置上次 stopChat 设置的 abort 标志（允许新请求重试）
        RetryableHttpTransport.resetAll();

        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            onError.accept(new RuntimeException("❌ 工作区未找到: " + workspaceId));
            return;
        }

        // 会话 → Workspace 映射（权限按 workspace 隔离）
        sessionWorkspaces.put(sessionId, workspaceId);

        // 每轮对话重置子 Agent 调度计数（循环防护基准）
        subagentCallCounts.remove(sessionId);
        // 新一轮用户消息：清除「确认恢复」标记，恢复常规 end 逻辑
        resuming.remove(sessionId);
        // 每轮重置工具连续失败计数
        toolFailCounts.remove(sessionId);

        // 清理上一轮遗留的挂起确认（刷新/离开页面导致的 ASKING 状态），
        // 避免新消息直接抛 "Agent is paused for human-in-the-loop confirmation"
        HarnessAgent agent = workspace.getAgent();
        RuntimeContext context = buildContext(workspace, sessionId);
        boolean cleared = clearStaleConfirmation(workspace, sessionId, agent);
        boolean polluted = cleanupPollutedContext(workspace, sessionId);
        if (cleared || polluted) {
            // 清理过挂起确认：重建 Agent 使其从清理后的状态文件加载（不阻塞、毫秒级）
            workspaceManager.rebuildAgent(workspaceId);
            workspace = workspaceManager.getWorkspace(workspaceId);
        }
        agent = workspace.getAgent();
        context = buildContext(workspace, sessionId);

        // Plan Mode 按会话隔离：预设 plan 文件路径 plans/{sessionId}/PLAN.md
        preconfigurePlanFile(workspace, sessionId);

        // 注入 Skill 提示（harness 自动发现所有 skill 并在 system prompt 里列出）
        // 用户选了 skill → 在用户消息前加一行引导，让 LLM 优先加载该 skill
        String skillHint = (skillName != null && !skillName.isBlank())
                ? "请使用 `" + skillName.trim() + "` skill 来完成以下任务。\n\n"
                : "";

        Msg userMsg = buildUserMessage(workspace, sessionId,
                skillHint + (message == null ? "" : message),
                attachments);

        // 会话转录：旧会话首次落盘时把 agent_state.json 已有历史种子化（快照），
        // 再追加本轮用户消息 —— 赶在未来的上下文压缩之前把历史固化
        recordUserTurn(workspace, sessionId, message, attachments);

        // 工具权限预授权：把 DB 永久规则 + 回合授权同步进本会话的 PermissionContextState，
        // 已授权工具在框架层直接静默执行（不发 REQUIRE_USER_CONFIRM、不注入任何恢复消息）
        syncPermissionRules(agent, workspace, sessionId, workspaceId);

        startStream(agent, context, userMsg, sessionId, true, false, onEvent, onError, onFinish);
    }

    /**
     * 会话状态目录：.easyClaw/agent/state/{userId}/{sessionId}
     */
    private Path sessionStateDir(WorkspaceContext workspace, String sessionId) {
        String userId = workspace.getUserId() == null
                ? AppConstants.DEFAULT_USER_ID : workspace.getUserId();
        return workspace.getPath().resolve(".easyClaw/agent/state")
                .resolve(userId).resolve(sessionId);
    }

    /**
     * 把本轮用户消息（含图片附件）追加进会话转录；转录不存在时先种子化已有历史。
     */
    private void recordUserTurn(WorkspaceContext workspace, String sessionId,
                                String message, List<UserAttachment> attachments) {
        try {
            Path dir = sessionStateDir(workspace, sessionId);
            SessionTranscriptStore.seedIfAbsent(dir, dir.resolve("agent_state.json"));
            BoxMessage bm = new BoxMessage(BoxMessage.Type.USER,
                    SessionTranscriptStore.countEntries(dir) + 1);
            bm.setContent(message == null ? "" : message);
            if (attachments != null) {
                List<String> images = new ArrayList<>();
                for (UserAttachment att : attachments) {
                    if (att != null && att.base64Data() != null && !att.base64Data().isBlank()
                            && att.mimeType() != null && att.mimeType().startsWith("image/")) {
                        images.add("data:" + att.mimeType() + ";base64," + att.base64Data());
                    }
                }
                if (!images.isEmpty()) {
                    bm.setImages(images);
                }
            }
            SessionTranscriptStore.append(dir, bm);
        } catch (Exception e) {
            log.warn("写入会话转录失败（忽略）: session={}, {}", sessionId, e.getMessage());
        }
    }

    /**
     * 为当前会话创建转录记录器（包装事件消费者）；会话未映射到工作区时返回 null（不记录）。
     */
    private TranscriptRecorder newTranscriptRecorder(String sessionId, Consumer<StreamEvent> onEvent) {
        try {
            String wid = sessionWorkspaces.get(sessionId);
            if (wid == null) {
                return null;
            }
            WorkspaceContext ws = workspaceManager.getWorkspace(wid);
            if (ws == null) {
                return null;
            }
            return new TranscriptRecorder(sessionStateDir(ws, sessionId), onEvent);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建多模态用户消息：文本 + 图片（ImageBlock）+ 文本附件（内容注入）。
     * 附件原件同时落盘（.easyClaw/agent/attachments/<sessionId>/），供追溯。
     */
    private Msg buildUserMessage(WorkspaceContext workspace, String sessionId,
                                 String message, List<UserAttachment> attachments) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (message != null && !message.isBlank()) {
            blocks.add(TextBlock.builder().text(message).build());
        }
        if (attachments != null) {
            for (UserAttachment att : attachments) {
                if (att.base64Data() == null || att.base64Data().isBlank()) {
                    continue;
                }
                try {
                    saveAttachment(workspace, sessionId, att);
                    if (att.mimeType() != null && att.mimeType().startsWith("image/")) {
                        blocks.add(ImageBlock.builder()
                                .source(new Base64Source(att.mimeType(), att.base64Data()))
                                .build());
                    } else {
                        String text = new String(Base64.getDecoder().decode(att.base64Data()), StandardCharsets.UTF_8);
                        if (!text.isBlank()) {
                            blocks.add(TextBlock.builder()
                                    .text("【附件 " + att.name() + "】\n" + text + "\n")
                                    .build());
                        }
                    }
                } catch (Exception e) {
                    log.warn("附件处理失败: {} - {}", att.name(), e.getMessage());
                }
            }
        }
        if (blocks.isEmpty()) {
            return new UserMessage(message == null ? "" : message);
        }
        return new UserMessage(blocks);
    }

    /**
     * 保存附件原件到 Workspace 的 .easyClaw/agent/attachments/<sessionId>/（沙箱外部，AI 不可见）
     */
    private void saveAttachment(WorkspaceContext workspace, String sessionId, UserAttachment att) {
        try {
            Path dir = workspace.getPath().resolve(".easyClaw/agent/attachments").resolve(sessionId);
            Files.createDirectories(dir);
            String safeName = att.name().replaceAll("[^a-zA-Z0-9._\\-]", "_");
            Files.write(dir.resolve(safeName), Base64.getDecoder().decode(att.base64Data()));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("保存附件失败: {} - {}", att.name(), e.getMessage());
        }
    }

    /**
     * 清理上一轮遗留的挂起确认（ASKING 状态的工具调用）。
     * <p>用户在确认弹窗出现时刷新/离开页面后，该暂停状态会被持久化到
     * agent_state.json；此时直接发新消息会抛
     * {@code IllegalStateException: Agent is paused for human-in-the-loop confirmation}。
     * 这里采用<b>文件级清理</b>（毫秒级、不阻塞请求线程）：把含 ASKING 工具调用的消息
     * 从 agent_state.json 的上下文中移除并回写，由调用方重建 Agent 从清理后的状态加载。</p>
     *
     * @return true 表示清理过挂起确认（调用方应 rebuildAgent）
     */
    private boolean clearStaleConfirmation(WorkspaceContext workspace, String sessionId, HarnessAgent agent) {
        try {
            Path stateFile = workspace.getPath().resolve(".easyClaw/agent/state")
                    .resolve(workspace.getUserId() == null ? AppConstants.DEFAULT_USER_ID : workspace.getUserId())
                    .resolve(sessionId).resolve("agent_state.json");
            if (!Files.exists(stateFile)) {
                return false;
            }
            AgentState state = AgentState.fromJsonString(Files.readString(stateFile));
            if (state.getContext() == null) {
                return false;
            }
            boolean hasAsking = state.getContext().stream()
                    .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                    .anyMatch(t -> t.getState() == ToolCallState.ASKING);
            if (!hasAsking) {
                return false;
            }
            // 移除含挂起工具调用的消息（保留其余历史上下文），回写状态文件
            List<Msg> mutable = state.contextMutable();
            int before = mutable.size();
            mutable.removeIf(m -> m.getContentBlocks(ToolUseBlock.class).stream()
                    .anyMatch(t -> t.getState() == ToolCallState.ASKING));
            Files.writeString(stateFile, state.toJson(), StandardCharsets.UTF_8);
            log.info("已清除挂起工具确认（移除 ASKING 消息 {}/{}）: session={}",
                    before - mutable.size(), before, sessionId);
            return true;
        } catch (Exception e) {
            log.warn("清理挂起确认失败（忽略）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清理上下文污染：
     * 1. 孤儿 ToolResultBlock（有 tool_call_id 但前面没有 assistant ToolUseBlock 声明过）
     * 2. 空 user 消息（content="" 且没有图片/附件，是 autoConfirmResume 注入的空消息）
     * 这两种污染会导致 OpenAI-compatible API（方舟）报 InvalidParameter，
     * 因为方舟严格校验 tool_call 链的配对关系。
     */
    private boolean cleanupPollutedContext(WorkspaceContext workspace, String sessionId) {
        try {
            Path stateFile = workspace.getPath().resolve(".easyClaw/agent/state")
                    .resolve(workspace.getUserId() == null ? AppConstants.DEFAULT_USER_ID : workspace.getUserId())
                    .resolve(sessionId).resolve("agent_state.json");
            if (!Files.exists(stateFile)) {
                return false;
            }
            AgentState state = AgentState.fromJsonString(Files.readString(stateFile));
            if (state.getContext() == null) {
                return false;
            }
            List<Msg> original = state.getContext();
            Set<String> declaredToolIds = new HashSet<>();
            for (Msg m : original) {
                for (ToolUseBlock tub : m.getContentBlocks(ToolUseBlock.class)) {
                    if (tub.getId() != null) {
                        declaredToolIds.add(tub.getId());
                    }
                }
            }
            List<Msg> cleaned = new ArrayList<>();
            int orphanToolResults = 0;
            int emptyUserMsgs = 0;
            for (Msg m : original) {
                if (m.getRole() == MsgRole.USER) {
                    String text = m.getTextContent();
                    boolean hasOnlyEmptyText = (text == null || text.isBlank())
                            && m.getContent() != null
                            && m.getContent().stream().allMatch(b -> b instanceof TextBlock tb
                                    && (tb.getText() == null || tb.getText().isBlank()));
                    boolean hasAttachments = m.getContent() != null && m.getContent().stream()
                            .anyMatch(b -> !(b instanceof TextBlock));
                    if (hasOnlyEmptyText && !hasAttachments) {
                        emptyUserMsgs++;
                        continue;
                    }
                }
                if (m.getContent() == null || m.getContent().isEmpty()) {
                    cleaned.add(m);
                    continue;
                }
                List<ContentBlock> newBlocks = new ArrayList<>();
                boolean changed = false;
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof ToolResultBlock trb) {
                        String id = trb.getId();
                        if (id != null && !declaredToolIds.contains(id)) {
                            orphanToolResults++;
                            changed = true;
                            continue;
                        }
                    }
                    newBlocks.add(b);
                }
                if (changed && newBlocks.isEmpty()) {
                    continue;
                }
                if (changed) {
                    cleaned.add(m.withContent(newBlocks));
                } else {
                    cleaned.add(m);
                }
            }
            if (orphanToolResults == 0 && emptyUserMsgs == 0) {
                return false;
            }
            state.contextMutable().clear();
            state.contextMutable().addAll(cleaned);
            Files.writeString(stateFile, state.toJson(), StandardCharsets.UTF_8);
            log.info("上下文净化: 移除孤儿 ToolResultBlock={}, 空 user 消息={}, 剩余消息 {}/{}: session={}",
                    orphanToolResults, emptyUserMsgs, cleaned.size(), original.size(), sessionId);
            return true;
        } catch (Exception e) {
            log.warn("上下文净化失败（忽略）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Plan Mode 按会话隔离：预设 agent_state.json 中 PlanModeContextState.currentPlanFile
     * 为 {@code plans/{sessionId}/PLAN.md}。
     * <p>AgentScope 框架的 PlanModeManager.enter() 只会在 currentPlanFile 为空时，
     * 才回退到默认的 {@code planDir + "/PLAN.md"}（由 Builder.planFileDirectory 配置）。
     * 提前按 session 注入路径，即可让不同会话的方案文件各自独立，互不覆盖。</p>
     */
    private void preconfigurePlanFile(WorkspaceContext workspace, String sessionId) {
        try {
            String userId = workspace.getUserId() == null
                    ? AppConstants.DEFAULT_USER_ID : workspace.getUserId();
            Path stateFile = workspace.getPath().resolve(".easyClaw/agent/state")
                    .resolve(userId).resolve(sessionId).resolve("agent_state.json");
            if (!Files.exists(stateFile)) {
                return;
            }
            AgentState state = AgentState.fromJsonString(Files.readString(stateFile));
            PlanModeContextState ctx = state.getPlanModeContext();
            if (ctx != null && ctx.getCurrentPlanFile() != null && !ctx.getCurrentPlanFile().isBlank()) {
                return;
            }
            String planFile = "plans/" + sessionId + "/PLAN.md";
            if (ctx == null) {
                ctx = new PlanModeContextState(false, planFile);
            } else {
                ctx.setCurrentPlanFile(planFile);
            }
            Files.writeString(stateFile, state.toJson(), StandardCharsets.UTF_8);
            log.debug("预设 Plan 文件路径: session={}, planFile={}", sessionId, planFile);
        } catch (Exception e) {
            log.warn("预设 Plan 文件路径失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 用户确认工具执行后恢复对话（允许/拒绝）
     */
    public void resumeChat(String workspaceId, String sessionId, boolean approved,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            onError.accept(new RuntimeException("❌ 工作区未找到: " + workspaceId));
            return;
        }
        HarnessAgent agent = workspace.getAgent();
        RuntimeContext context = buildContext(workspace, sessionId);

        preconfigurePlanFile(workspace, sessionId);

        // 会话 → Workspace 映射（权限按 workspace 隔离）
        sessionWorkspaces.put(sessionId, workspaceId);
        // 标记进入「确认恢复」阶段：恢复流统一负责发送 end，原暂停流不再发送
        resuming.put(sessionId, new AtomicBoolean(true));

        List<ToolUseBlock> tools = pendingConfirms.remove(sessionId);
        // 无待确认工具（可能已自动放行、或确认请求已过期/重复）：直接结束，避免起一个幽灵空轮
        if (tools == null || tools.isEmpty()) {
            log.warn("resumeChat 无待确认工具（可能已自动放行或过期），直接结束: sessionId={}", sessionId);
            onFinish.run();
            return;
        }
        List<ConfirmResult> results = new ArrayList<>();
        if (tools != null) {
            for (ToolUseBlock t : tools) {
                // 已授权（回合/永久）的工具总是放行；未授权的按用户本次选择
                boolean allowed = isAllowed(sessionId, t.getName()) || approved;
                results.add(new ConfirmResult(allowed, t));
            }
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        // 恢复消息带明确语义文本（不能是空串）：空 user 消息会留在模型上下文里，
        // 多次工具确认后模型会看到连续多条"空消息"，误判为网络问题/误触并输出困惑回复
        Msg resume = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(approved ? "（已确认：允许执行上述工具）" : "（已确认：拒绝执行上述工具）")
                .metadata(meta)
                .build();

        log.info("用户{}了工具执行: workspaceId={}, sessionId={}, tools={}",
                approved ? "允许" : "拒绝", workspaceId, sessionId, tools == null ? 0 : tools.size());

        // 先把回合/永久授权同步为 ALLOW 规则：恢复流里模型若再次调用同类工具，
        // 框架直接静默执行（不再弹窗、不注入消息）——避免"确认→恢复→再确认"循环
        syncPermissionRules(agent, workspace, sessionId, workspaceId);

        startStream(agent, context, resume, sessionId, false, true, onEvent, onError, onFinish);
    }

    // ==================== 流式处理 ====================

    /** burst 合并窗口（毫秒）：仅合并 8ms 内连续到达的 delta（正常 LLM 50-100ms/token 不触发） */
    private static final long BURST_WINDOW_MS = 8;
    /** burst 合并上限（字符）：buffer 到 60 字符立即 flush，不等窗口 */
    private static final int BURST_MAX_CHARS = 60;

    /**
     * 极轻量 burst 合并器：首字零延迟直接发，仅当 LLM 短时间密集输出（8ms 内多 delta）
     * 才累积合并。正常节奏 50-100ms/token 的输出不会触发累积，每个 delta 直接 emit。
     * 非 delta 事件（tool/confirm/agent_end）到来时 flush 保证顺序。
     */
    private static final class DeltaBatcher {
        private final StringBuilder textBuf = new StringBuilder();
        private final StringBuilder thinkBuf = new StringBuilder();
        private final Map<String, StringBuilder> subagentBufs = new HashMap<>();
        private long lastEmitAt = 0;
        private final Consumer<StreamEvent> emitter;

        DeltaBatcher(Consumer<StreamEvent> emitter) {
            this.emitter = emitter;
        }

        void onText(String delta) {
            if (textBuf.length() == 0 && thinkBuf.length() == 0 && subagentBufs.isEmpty()) {
                emitter.accept(StreamEvent.text(delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                textBuf.append(delta);
                tryBurstFlush();
            }
        }

        void onReasoning(String delta) {
            if (textBuf.length() == 0 && thinkBuf.length() == 0 && subagentBufs.isEmpty()) {
                emitter.accept(StreamEvent.reasoning(delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                thinkBuf.append(delta);
                tryBurstFlush();
            }
        }

        void onSubagentText(String subName, String delta) {
            if (textBuf.length() == 0 && thinkBuf.length() == 0 && subagentBufs.isEmpty()) {
                emitter.accept(StreamEvent.subagentText(subName, delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                subagentBufs.computeIfAbsent(subName, k -> new StringBuilder()).append(delta);
                tryBurstFlush();
            }
        }

        /** 非 delta 事件到来前调用，保证 delta 在非 delta 事件之前发出 */
        void flush() {
            if (textBuf.length() > 0) {
                emitter.accept(StreamEvent.text(textBuf.toString()));
                textBuf.setLength(0);
            }
            if (thinkBuf.length() > 0) {
                emitter.accept(StreamEvent.reasoning(thinkBuf.toString()));
                thinkBuf.setLength(0);
            }
            if (!subagentBufs.isEmpty()) {
                for (Map.Entry<String, StringBuilder> e : subagentBufs.entrySet()) {
                    if (e.getValue().length() > 0) {
                        emitter.accept(StreamEvent.subagentText(e.getKey(), e.getValue().toString()));
                    }
                }
                subagentBufs.clear();
            }
            lastEmitAt = System.currentTimeMillis();
        }

        private void tryBurstFlush() {
            long now = System.currentTimeMillis();
            if (now - lastEmitAt < BURST_WINDOW_MS
                    && textBuf.length() < BURST_MAX_CHARS
                    && thinkBuf.length() < BURST_MAX_CHARS
                    && subagentTotalChars() < BURST_MAX_CHARS) {
                return;
            }
            flush();
        }

        private int subagentTotalChars() {
            int total = 0;
            for (StringBuilder sb : subagentBufs.values()) {
                total += sb.length();
            }
            return total;
        }
    }

    private void startStream(HarnessAgent agent, RuntimeContext context, Msg msg, String sessionId,
                             boolean mainTurn, boolean isResumeStream,
                             Consumer<StreamEvent> onEvent,
                             Consumer<Throwable> onError,
                             Runnable onFinish) {
        debugLogContext(agent, sessionId);
        final boolean[] ended = {false};
        final boolean[] retried = {false};
        final ToolTrace trace = new ToolTrace();
        // 会话转录：把推送给 UI 的完整事件流聚合落盘（append-only），与模型上下文压缩解耦，
        // 保证历史消息不因 compaction / agent 重建而丢失。落盘失败不影响主流程。
        final TranscriptRecorder recorder = newTranscriptRecorder(sessionId, onEvent);
        final Consumer<StreamEvent> eventSink = recorder != null ? recorder : onEvent;
        final DeltaBatcher batcher = new DeltaBatcher(eventSink);

        // 确保同一会话只有一个活跃订阅：发起新流前先释放上一轮（暂停中等候确认）的订阅，
        // 避免原暂停流与「确认恢复流」重复推送事件 / 重复发送 end。
        // 注：AgentScope 的暂停状态已持久化到 state 文件，释放原事件流不会丢失待确认上下文，
        // 新流（resumeChat / autoConfirmResume）凭确认结果从同一状态恢复执行。
        Disposable prev = sessionDisposables.remove(sessionId);
        if (prev != null && !prev.isDisposed()) {
            try {
                prev.dispose();
                log.debug("已释放上轮订阅（避免重复事件）: sessionId={}", sessionId);
            } catch (Exception ignored) {
                // 释放失败不影响新流
            }
        }

        Disposable disp = agent.streamEvents(msg, context)
                // 不设硬超时：LLM 思考/模型响应可能很久，事件流由 AgentScope 生命周期
                // （AGENT_END / 错误 / 用户中断）控制结束，避免长思考被误杀
                .doOnNext(event -> {
                    handleEvent(event, eventSink, onError, onFinish, trace, sessionId, agent, batcher);
                    // 收到 AGENT_END 即认为回复完成，立即复位 UI（不依赖 Flux complete）
                    if (event.getType() == AgentEventType.AGENT_END) {
                        // 回合结束但仍有在途工具（TOOL_RESULT_END 丢失的异常路径）：补发收尾，避免 UI 卡"执行中"
                        closeInFlightTool(trace, eventSink, "已结束");
                        ended[0] = true;
                        batcher.flush();
                        if (recorder != null) {
                            recorder.flushAll();
                        }
                        emitContextStatus(agent, eventSink);
                        // NO_REPLY 兜底：主回合调用了工具但无任何文本输出（模型输出 NO_REPLY 被
                        // MemoryFlush 压缩询问吞掉等场景）→ 自动续问一次，强制给用户总结
                        if (mainTurn && trace.toolCalled && !trace.hasText && !retried[0]) {
                            retried[0] = true;
                            log.info("主回合调用了工具但无文本回复（NO_REPLY?），自动续问总结: session={}", sessionId);
                            Msg followUp = Msg.builder()
                                    .name("user")
                                    .role(MsgRole.USER)
                                    .textContent("（系统提示）请基于刚才的工具执行结果，直接给用户完整、清晰的中文总结回复。"
                                            + "不要输出 NO_REPLY，不要重复工具调用，直接总结。")
                                    .build();
                            startStream(agent, context, followUp, sessionId, false, false,
                                    onEvent, onError, onFinish);
                            return;
                        }
                        finishTurn(sessionId, isResumeStream, onFinish);
                    }
                })
                .doOnError(err -> {
                    log.error("流式对话执行失败: {}", err.getMessage(), err);
                    // 流错误时在途工具补发收尾，避免 UI 卡"执行中"
                    closeInFlightTool(trace, eventSink, "已中断（流错误）");
                    batcher.flush();
                    if (recorder != null) {
                        recorder.flushAll();
                    }
                    onError.accept(err);
                    // Agent 内部状态可能已损坏（模型 API 失败/流中断）：
                    // 重建 Agent，避免后续消息"无响应"（新消息从 state 文件干净加载）
                    try {
                        String wid = sessionWorkspaces.get(sessionId);
                        if (wid != null) {
                            workspaceManager.rebuildAgent(wid);
                            log.info("流式错误后已重建 Agent: workspaceId={}", wid);
                        }
                    } catch (Exception ignore) {
                        // 重建失败不影响错误上报
                    }
                })
                .doFinally(signal -> {
                    batcher.flush();
                    // 转录兜底落盘：流被 dispose（确认暂停/停止）或异常终止时不丢已产出内容
                    if (recorder != null) {
                        recorder.flushAll();
                    }
                    // 清理本会话的订阅引用（已结束/出错/被 dispose 都要清）
                    sessionDisposables.remove(sessionId);
                    // 流终止（complete/error/cancel）兜底：确保 UI 一定复位，状态最终落盘
                    if (mainTurn) {
                        // 主回合结束：清空"本回合允许"授权，避免残留导致后续写工具不再弹窗
                        turnAllowed.remove(sessionId);
                    }
                    // Agent 暂停等待用户确认（pendingConfirms 未清）：此时事件流会 complete，
                    // 但绝不能 complete 掉 SSE 连接 —— 否则 confirm 弹窗后的 resume 事件无处推送。
                    // 保持连接，待 resumeChat 清空 pendingConfirms 后由 resume 的流正常收尾。
                    if (pendingConfirms.containsKey(sessionId)) {
                        log.info("Agent 暂停等待确认，保持 SSE 连接: sessionId={}", sessionId);
                        return;
                    }
                    // 流终止兜底（被新消息顶替 dispose / 用户停止 / 异常取消）：
                    // 在途工具补发收尾事件，避免 UI 工具卡片永久停留在"执行中"
                    closeInFlightTool(trace, eventSink, "已中断");
                    if (recorder != null) {
                        recorder.flushAll();
                    }
                    if (!ended[0]) {
                        try {
                            emitContextStatus(agent, eventSink);
                        } catch (Exception ignored) {
                            // 状态读取失败不影响复位
                        }
                        finishTurn(sessionId, isResumeStream, onFinish);
                    }
                })
                .subscribe();
        sessionDisposables.put(sessionId, disp);
    }

    /**
     * 流终止时为在途工具（TOOL_CALL_START 后未收到 TOOL_RESULT_END）补发收尾事件，
     * 避免 UI 工具卡片永久停留在"执行中"。幂等：关闭后 toolInFlight 置 false，重复调用无副作用。
     */
    private void closeInFlightTool(ToolTrace trace, Consumer<StreamEvent> sink, String note) {
        if (trace == null || !trace.toolInFlight || trace.toolName == null || trace.toolName.isEmpty()) {
            return;
        }
        trace.toolInFlight = false;
        try {
            sink.accept(StreamEvent.toolResult("(" + note + ")"));
            sink.accept(StreamEvent.toolEnd(trace.toolName));
        } catch (Exception ignored) {
            // 收尾失败不影响主流程
        }
    }

    /**
     * 统一发送本回合 end 事件，确保「确认恢复」阶段 end 只由恢复流发送一次：
     * <ul>
     *   <li>isResumeStream=false（用户消息 / 自动续问的原流）：若正处于确认恢复阶段（resuming=true），
     *       说明该流已被恢复流取代，抑制其 end，避免 UI 提前复位、后续文本丢失；否则正常发送。</li>
     *   <li>isResumeStream=true（确认恢复流）：正常发送 end，并清除 resuming 标记，后续回合恢复常规逻辑。</li>
     * </ul>
     */
    private void finishTurn(String sessionId, boolean isResumeStream, Runnable onFinish) {
        boolean isResuming = resuming.getOrDefault(sessionId, new AtomicBoolean(false)).get();
        if (!isResumeStream && isResuming) {
            log.debug("抑制旧流(end)：确认恢复流将统一发送, sessionId={}", sessionId);
            return;
        }
        onFinish.run();
        if (isResumeStream) {
            resuming.remove(sessionId);
        }
    }

    /** 工具调用追溯状态（名称 / 参数 / 结果，按会话隔离） */
    private static final class ToolTrace {
        String toolName = "";
        boolean toolCalled = false;
        boolean hasText = false;
        /** 在途标记：TOOL_CALL_START 后、TOOL_RESULT_END 前为 true，用于流终止时补发收尾 */
        boolean toolInFlight = false;
        final StringBuilder args = new StringBuilder();
        final StringBuilder result = new StringBuilder();
    }

    /**
     * 子 Agent 事件隔离路由：其文本 / 思考 / 工具调用 / 结果全部进入
     * 子 Agent 自己的折叠块（subagent_text 事件），绝不混入主流程。
     * TEXT/THINKING delta 通过 batcher 合并；工具调用/结果等非 delta 事件立即 flush 后发送。
     */
    private void handleSubagentEvent(AgentEvent event, String subName, Consumer<StreamEvent> onEvent,
                                     DeltaBatcher batcher) {
        String source = event.getSource();
        if (event instanceof TextBlockDeltaEvent e) {
            batcher.onSubagentText(subName, e.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            batcher.onSubagentText(subName, "🧠 " + e.getDelta());
        } else if (event.getType() == AgentEventType.EXCEED_MAX_ITERS) {
            // 子 Agent 迭代耗尽：这是「子 Agent 回复被截断」最常见的原因。
            // 声明文件的 steps（框架默认仅 10）用尽后框架强行结束，此前无任何提示。
            batcher.flush();
            int max = event instanceof ExceedMaxItersEvent e ? e.getMaxIters() : -1;
            int cur = event instanceof ExceedMaxItersEvent e2 ? e2.getCurrentIter() : -1;
            log.warn("子 Agent [{}] 达到迭代上限，回复被强制结束: currentIter={}, maxIters={}",
                    subName, cur, max);
            onEvent.accept(StreamEvent.subagentText(subName,
                    "\n⚠️ 已达迭代上限（" + cur + "/" + max + " 步），子 Agent 回复被强制结束。"
                            + "可在该子 Agent 声明的 frontmatter 里调高 steps。"));
        } else if (event.getType() == AgentEventType.AGENT_END) {
            // 子 Agent 结束：先 flush 剩余 delta，再发 subagent_end 标记
            batcher.flush();
            onEvent.accept(StreamEvent.subagentEnd(subName));
        } else {
            // 非 delta 事件：先 flush 累积的 subagent delta，保证顺序正确
            batcher.flush();
            if (event instanceof ToolCallStartEvent e) {
                subagentResultBuffers.remove(source);
                onEvent.accept(StreamEvent.subagentText(subName, "\n🔧 调用: " + e.getToolCallName()));
            } else if (event instanceof ToolCallDeltaEvent e) {
                onEvent.accept(StreamEvent.subagentText(subName, e.getDelta()));
            } else if (event instanceof ToolResultTextDeltaEvent e) {
                subagentResultBuffers.computeIfAbsent(source, k -> new StringBuilder()).append(e.getDelta());
                onEvent.accept(StreamEvent.subagentText(subName, e.getDelta()));
            } else if (event instanceof ToolResultEndEvent e) {
                String result = String.valueOf(subagentResultBuffers.remove(source));
                String state = inferToolResultState(
                        e.getState() != null ? e.getState().name() : null,
                        result);
                onEvent.accept(StreamEvent.subagentText(subName, "\n📤 结果(" + state + ")"));
            }
        }
        // 其余子 Agent 内部事件忽略（不进入主流程）
    }

    /**
     * AgentScope 2.0.0 存在 bug：框架级错误（参数校验失败、工具不存在、无权限等）
     * 的 ToolResultBlock 文本前缀是 "Error: "，但 determineToolResultState() 只检查 "[ERROR]"，
     * 导致 getState() 返回 null，默认被推断为 SUCCESS。
     * <p>本方法额外根据结果文本内容推断真实状态，修正错误分类。</p>
     * <p>设计原则：只匹配 AgentScope 框架错误的精确特征，避免把 read_file 返回的
     * 代码内容（可能含 error: 属性、failed 注释等）误判为 ERROR。</p>
     */
    private static String inferToolResultState(String state, String resultText) {
        if (state != null && !"SUCCESS".equalsIgnoreCase(state) && !"RUNNING".equalsIgnoreCase(state)) {
            return state;
        }
        if (resultText == null || resultText.isBlank()) {
            return state != null ? state : "SUCCESS";
        }
        String trimmed = resultText.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // AgentScope 框架级错误的精确特征：
        // 1) 以明确的错误标记开头（框架级错误都是短消息 + 明确前缀）
        if (trimmed.startsWith("[ERROR]")
                || trimmed.startsWith("Error: ")
                || trimmed.startsWith("❌ ")
                || trimmed.startsWith("Exception: ")
                || trimmed.startsWith("Caused by: ")) {
            return "ERROR";
        }
        // 2) AgentScope 特有的框架错误短语（精确匹配整条消息的前几个词）
        if (lower.startsWith("tool not found")
                || lower.startsWith("unknown tool")
                || lower.startsWith("tool execution failed")
                || lower.startsWith("parameter validation failed")
                || lower.startsWith("insufficient permissions")
                || lower.startsWith("unauthorized tool call")
                || lower.startsWith("string not found in file")
                || lower.startsWith("file not found")
                || lower.startsWith("permission denied")
                || lower.startsWith("illegal argument")
                || lower.startsWith("illegalargumentexception")) {
            return "ERROR";
        }
        // 3) 短文本（< 200 字符）且不含引号/括号开头——几乎肯定是框架错误
        //    正常工具返回要么很长，要么以代码/数据开头
        if (trimmed.length() < 200
                && !trimmed.startsWith("\"")
                && !trimmed.startsWith("{")
                && !trimmed.startsWith("[")
                && !trimmed.startsWith("`")
                && !trimmed.startsWith("<")
                && !trimmed.startsWith("package ")
                && !trimmed.startsWith("import ")
                && !trimmed.contains("\n")
                && (lower.contains("failed")
                        || lower.contains("error")
                        || lower.contains("not found")
                        || lower.contains("failed to"))) {
            return "ERROR";
        }

        return state != null ? state : "SUCCESS";
    }

    private void handleEvent(AgentEvent event, Consumer<StreamEvent> onEvent,
                             Consumer<Throwable> onError, Runnable onFinish,
                             ToolTrace trace, String sessionId, HarnessAgent agent,
                             DeltaBatcher batcher) {
        // 子 Agent 转发的事件（source 形如 "main/reviewer"）：全部隔离到子 Agent 折叠块，
        // 不混入主流程（文本/思考/工具调用/结果均隔离）。RequireUserConfirmEvent 除外，
        // 工具确认由主控统一管理（弹窗征求用户意见）。
        String source = event.getSource();
        boolean fromSubagent = source != null && source.contains("/")
                && !(event instanceof RequireUserConfirmEvent);
        if (fromSubagent) {
            handleSubagentEvent(event, source.substring(source.lastIndexOf('/') + 1), onEvent, batcher);
            return;
        }
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> {
                if (event instanceof TextBlockDeltaEvent e) {
                    trace.hasText = true;
                    batcher.onText(e.getDelta());
                }
            }
            case THINKING_BLOCK_DELTA -> {
                if (event instanceof ThinkingBlockDeltaEvent e) {
                    batcher.onReasoning(e.getDelta());
                }
            }
            default -> {
                // 非 delta 事件：先 flush 累积的 delta，保证事件顺序正确
                batcher.flush();
                handleNonDeltaEvent(event, onEvent, onError, onFinish, trace, sessionId, agent);
            }
        }
    }

    /** 非 delta 事件的处理（tool_start/end/result, confirm, subagent 等） */
    private void handleNonDeltaEvent(AgentEvent event, Consumer<StreamEvent> onEvent,
                                     Consumer<Throwable> onError, Runnable onFinish,
                                     ToolTrace trace, String sessionId, HarnessAgent agent) {
        switch (event.getType()) {
            case EXCEED_MAX_ITERS -> {
                // 迭代耗尽：框架会直接结束本轮，之前是静默的 → 回复看起来「无故截断」。
                // 这里显式告知用户，避免把框架限制误认为模型能力问题。
                int max = event instanceof ExceedMaxItersEvent e ? e.getMaxIters() : -1;
                int cur = event instanceof ExceedMaxItersEvent e2 ? e2.getCurrentIter() : -1;
                log.warn("达到 ReAct 迭代上限，回复被强制结束: session={}, currentIter={}, maxIters={}",
                        sessionId, cur, max);
                onEvent.accept(StreamEvent.error(
                        "⚠️ 已达到迭代上限（" + cur + "/" + max + " 步），回复被强制结束。"
                                + "可在配置中调高 easyclaw.agentscope.agent.max-iters 后重试。"));
            }
            case TOOL_CALL_START -> {
                if (event instanceof ToolCallStartEvent e) {
                    String name = e.getToolCallName();
                    trace.toolCalled = true;
                    trace.toolName = name;
                    trace.toolInFlight = true;
                    trace.args.setLength(0);
                    if (name != null && name.toLowerCase().contains("subagent")) {
                        String subName = extractSubagentName(name);
                        onEvent.accept(StreamEvent.subagent(subName));
                        // 循环调度防护：同一会话内同一子 Agent 超过 3 次 → 打断
                        int count = subagentCallCounts
                                .computeIfAbsent(sessionId, k -> new HashMap<>())
                                .merge(subName, 1, Integer::sum);
                        if (count > 3) {
                            log.warn("检测到子 Agent 循环调度: session={}, subagent={}, count={}", sessionId, subName, count);
                            try {
                                agent.interrupt();
                            } catch (Exception ignored) {
                                // 打断失败不影响后续
                            }
                            onEvent.accept(StreamEvent.context(
                                    "{\"type\":\"loop_warning\",\"subagent\":\"" + subName
                                            + "\",\"count\":" + count
                                            + ",\"message\":\"检测到子 Agent[" + subName + "] 循环调度（" + count
                                            + " 次），已自动停止。请在后续指令中明确要求不要重复调度同一子 Agent。\"}"));
                        }
                    } else {
                        onEvent.accept(StreamEvent.tool(name));
                    }
                }
            }
            case TOOL_CALL_DELTA -> {
                if (event instanceof ToolCallDeltaEvent e) {
                    trace.args.append(e.getDelta());
                }
            }
            case TOOL_CALL_END -> {
                String args = trace.args.toString().trim();
                onEvent.accept(StreamEvent.toolArgs(args.isEmpty() ? "(无参数)" : args));
            }
            case TOOL_RESULT_START -> {
                trace.result.setLength(0);
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                if (event instanceof ToolResultTextDeltaEvent e) {
                    trace.result.append(e.getDelta());
                }
            }
            case TOOL_RESULT_END -> {
                trace.toolInFlight = false;
                String result = trace.result.toString().trim();
                String state = event instanceof ToolResultEndEvent e
                        ? inferToolResultState(
                                e.getState() != null ? e.getState().name() : null,
                                result)
                        : inferToolResultState(null, result);
                onEvent.accept(StreamEvent.toolResult("(" + state + ") "
                        + (result.isEmpty() ? "(空结果)" : result)));
                // 工具连续失败护栏：同一工具连续失败 ≥2 次时注入停止提示
                if ("ERROR".equalsIgnoreCase(state)) {
                    int fails = toolFailCounts
                            .computeIfAbsent(sessionId, k -> new HashMap<>())
                            .merge(trace.toolName, 1, Integer::sum);
                    if (fails >= 2) {
                        log.warn("工具连续失败护栏触发: session={}, tool={}, fails={}",
                                sessionId, trace.toolName, fails);
                        onEvent.accept(StreamEvent.context(
                                "{\"type\":\"tool_fail_guard\",\"tool\":\"" + trace.toolName
                                + "\",\"fails\":" + fails
                                + ",\"message\":\"工具[" + trace.toolName + "] 连续失败 " + fails
                                + " 次，请停止重试，检查参数/路径/权限后向用户说明并询问。\"}"));
                    }
                } else {
                    // 成功则重置该工具的失败计数
                    toolFailCounts.computeIfPresent(sessionId, (k, m) -> {
                        m.remove(trace.toolName);
                        return m;
                    });
                }
                onEvent.accept(StreamEvent.toolEnd(trace.toolName));
            }
            case REQUIRE_USER_CONFIRM -> {
                // 工具执行前需用户确认：只有未被预授权规则覆盖的工具才会走到这里（弹窗）。
                // 已授权（永久/回合）的工具在 streamChat 入口已同步为 ALLOW 规则，
                // 框架直接静默执行，不触发本事件、也不注入任何恢复消息（LLM 无感）。
                // 拒绝时由框架回 "Permission denied by user" 工具结果给 LLM（有感）。
                if (event instanceof RequireUserConfirmEvent e) {
                    List<ToolUseBlock> tools = e.getToolCalls();
                    pendingConfirms.put(sessionId, tools);
                    log.info("收到工具确认请求: session={}, tools={}",
                            sessionId,
                            tools.stream().map(ToolUseBlock::getName).toList());
                    onEvent.accept(StreamEvent.confirm(buildConfirmJson(e.getReplyId(), tools)));
                }
            }
            case SUBAGENT_EXPOSED -> {
                onEvent.accept(StreamEvent.subagent(extractSubagentName(String.valueOf(event.getId()))));
            }
            default -> {
                // 其他事件忽略
            }
        }
    }

    private String buildConfirmJson(String replyId, List<ToolUseBlock> tools) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("replyId", replyId);
            List<Map<String, Object>> list = new ArrayList<>();
            if (tools != null) {
                for (ToolUseBlock t : tools) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", t.getId());
                    item.put("name", t.getName());
                    item.put("input", t.getInput());
                    list.add(item);
                }
            }
            root.put("tools", list);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"replyId\":\"" + replyId + "\",\"tools\":[]}";
        }
    }

    private RuntimeContext buildContext(WorkspaceContext workspace, String sessionId) {
        String userId = workspace.getUserId() == null ? AppConstants.DEFAULT_USER_ID : workspace.getUserId();
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                // 注入 Workspace 上下文给文件工具（沙箱校验），不暴露给 LLM
                .put(WorkspaceContext.class, workspace)
                .build();
    }

    private String extractSubagentName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "子 Agent";
        }
        String s = raw.replace("subagent_", "").replace("subagent-", "");
        return s.isBlank() ? "子 Agent" : s;
    }

    /**
     * 诊断日志：打印当前上下文所有消息的 role / tool_calls / tool_call_id 配对情况，
     * 用于排查 "tool_call_id is not found" 类上下文断裂问题。
     */
    private void debugLogContext(HarnessAgent agent, String sessionId) {
        try {
            AgentState state = agent.getAgentState();
            if (state == null || state.getContext() == null) {
                return;
            }
            List<Msg> ctx = state.getContext();
            StringBuilder sb = new StringBuilder();
            sb.append("[CTX] session=").append(sessionId).append(", msgCount=").append(ctx.size()).append("\n");
            for (int i = 0; i < ctx.size(); i++) {
                Msg m = ctx.get(i);
                sb.append("  [").append(i).append("] role=").append(m.getRole());
                sb.append(", name=").append(m.getName());
                sb.append(", blocks=[");
                var blocks = m.getContent();
                for (int j = 0; j < blocks.size(); j++) {
                    var b = blocks.get(j);
                    sb.append(b.getClass().getSimpleName());
                    switch (b) {
                        case ToolUseBlock tub -> sb.append("(id=").append(tub.getId())
                                .append(", tool=").append(tub.getName())
                                .append(", state=").append(tub.getState()).append(")");
                        case ToolResultBlock trb -> sb.append("(id=").append(trb.getId())
                                .append(", name=").append(trb.getName())
                                .append(", state=").append(trb.getState()).append(")");
                        case TextBlock tb ->
                                sb.append("(len=").append(tb.getText() == null ? 0 : tb.getText().length()).append(")");
                        case ThinkingBlock th ->
                                sb.append("(len=").append(th.getThinking() == null ? 0 : th.getThinking().length()).append(")");
                        default -> {
                        }
                    }
                    if (j < blocks.size() - 1) sb.append(", ");
                }
                sb.append("]\n");
            }
            log.info(sb.toString());
        } catch (Exception ignore) {
        }
    }

    /**
     * 对话结束后查询 AgentState，输出上下文状态（消息数 / 估算 token / 是否已压缩）
     */
    private void emitContextStatus(HarnessAgent agent, Consumer<StreamEvent> onEvent) {
        try {
            AgentState state = agent.getAgentState();
            if (state == null) {
                return;
            }
            List<Msg> context = state.getContext();
            int messageCount = context == null ? 0 : context.size();
            long chars = 0;
            if (context != null) {
                for (Msg m : context) {
                    String t = m.getTextContent();
                    if (t != null) {
                        chars += t.length();
                    }
                }
            }
            long tokens = Math.max(1, chars / 4);
            String summary = state.getSummary();
            boolean compacted = summary != null && !summary.isBlank();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messages", messageCount);
            payload.put("tokens", tokens);
            payload.put("compacted", compacted);
            onEvent.accept(StreamEvent.context(mapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("读取上下文状态失败: {}", e.getMessage());
        }
    }

    /**
     * 使用指定 Workspace 的 Agent 进行对话（同步，一次性返回）
     */
    public ChatResponse chat(String workspaceId, String sessionId, String message) {
        return chat(workspaceId, sessionId, message, null);
    }

    /**
     * 使用指定 Workspace 的 Agent 进行对话（同步，可指定角色）
     */
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

            HarnessAgent agent = workspace.getAgent();
            RuntimeContext context = buildContext(workspace, sessionId);

            Msg result = agent.call(new UserMessage(message), context).block();

            String content = result != null ? result.getTextContent() : "AI 未返回任何内容";

            String agentName = roleName != null ? roleName : "workspace-agent";
            return new ChatResponse(content, "markdown", agentName);
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
