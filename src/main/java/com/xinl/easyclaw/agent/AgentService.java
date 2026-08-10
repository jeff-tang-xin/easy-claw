package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.ChatMode;
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
import com.xinl.easyclaw.workspace.entity.WorkspaceConfigEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
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
    private final WorkspaceConfigRepository configRepository;
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

    public AgentService(WorkspaceManager workspaceManager,
                        AgentFactory agentFactory,
                        PermissionRuleService permissionRuleService,
                        WorkspaceConfigRepository configRepository) {
        this.workspaceManager = workspaceManager;
        this.agentFactory = agentFactory;
        this.permissionRuleService = permissionRuleService;
        this.configRepository = configRepository;
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
        try { workspaceManager.rebuildAgent(workspaceId); } catch (Exception e) { log.warn("重建 Agent 失败: {}", e.getMessage()); }
    }

    /**
     * 撤销指定 Workspace 的永久授权（删除后立即生效）
     */
    public void revokePermanently(String workspaceId, String toolName) {
        permissionRuleService.remove(workspaceId, toolName);
        log.info("已撤销永久授权: workspaceId={}, tool={}", workspaceId, toolName);
        try { workspaceManager.rebuildAgent(workspaceId); } catch (Exception e) { log.warn("重建 Agent 失败: {}", e.getMessage()); }
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
     *   <li>调用 agent.interrupt()（AgentScope 内部设置中断标志，让 Agent 循环提前退出）</li>
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
        WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws != null && ws.getAgent() != null) {
            try {
                ws.getAgent().interrupt();
                log.info("已调用 agent.interrupt(): workspaceId={}", workspaceId);
            } catch (Exception e) {
                log.warn("agent.interrupt() 异常: workspaceId={}, err={}", workspaceId, e.getMessage());
            }
        }

        pendingConfirms.remove(sessionId);
        turnAllowed.remove(sessionId);
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
                ChatMode.BaseMode.PLAN, null,
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
                ChatMode.BaseMode.PLAN, null,
                onEvent, onError, onFinish);
    }

    /**
     * 流式对话入口：模式 + Skill 通过 prompt 前缀注入（不重建 Agent）。
     * <p>
     * 模式：PLAN（Plan Mode 原生开启 + 深度推理）/ QUICK（自由执行）。
     * Skill：可选 .md 文件全文注入，工作区优先 → 全局回退。
     * 模式来源优先级：前端显式传入 > workspace 配置默认值 > PLAN。
     *
     * @param baseMode  基础模式，null 表示使用 workspace 默认值或 PLAN
     * @param skillName 注入的 Skill 名称，null 表示不注入
     */
    public void streamChat(String workspaceId, String sessionId, String message,
                           List<UserAttachment> attachments,
                           ChatMode.BaseMode baseMode, String skillName,
                           Consumer<StreamEvent> onEvent,
                           Consumer<Throwable> onError,
                           Runnable onFinish) {
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

        // 清理上一轮遗留的挂起确认（刷新/离开页面导致的 ASKING 状态），
        // 避免新消息直接抛 "Agent is paused for human-in-the-loop confirmation"
        HarnessAgent agent = workspace.getAgent();
        RuntimeContext context = buildContext(workspace, sessionId);
        boolean cleared = clearStaleConfirmation(workspace, sessionId, agent);
        if (cleared) {
            // 清理过挂起确认：重建 Agent 使其从清理后的状态文件加载（不阻塞、毫秒级）
            workspaceManager.rebuildAgent(workspaceId);
            workspace = workspaceManager.getWorkspace(workspaceId);
        }
        agent = workspace.getAgent();
        context = buildContext(workspace, sessionId);

        // Plan Mode 状态管理：
        //   PLAN（默认）→ 预设 plan 文件路径 plans/{sessionId}/PLAN.md，
        //                让框架 enter() 不回退到默认 plans/PLAN.md，实现会话隔离。
        //   QUICK       → 强制退出 plan mode（清空 PlanModeContextState），
        //                确保不在只读限制模式下，agent 可以自由执行。
        // 模式来源优先级：前端显式传入 > workspace 配置默认值 > PLAN
        ChatMode.BaseMode effectiveBase = resolveBaseMode(workspaceId, baseMode);
        if (effectiveBase == ChatMode.BaseMode.PLAN) {
            preconfigurePlanFile(workspace, sessionId);
        } else {
            exitPlanMode(workspace, sessionId);
        }

        // 关键：Plan Mode 状态变化写入磁盘后，必须重建 Agent 让它从新磁盘状态加载。
        // 否则 agent 内存里的 PlanModeContextState 仍是旧值（比如 planActive=true），
        // PlanModeManager 会继续认为在规划中、保持只读限制。
        workspaceManager.rebuildAgent(workspaceId);
        workspace = workspaceManager.getWorkspace(workspaceId);
        agent = workspace.getAgent();
        context = buildContext(workspace, sessionId);

        log.info("流式对话开始: workspaceId={}, sessionId={}, baseMode={}, skill={}, message={}, attachments={}",
                workspaceId, sessionId,
                effectiveBase,
                skillName,
                message == null ? "" : message.substring(0, Math.min(50, message.length())),
                attachments == null ? 0 : attachments.size());

        String modePrefix = buildModeSkillPrefix(workspace, effectiveBase, skillName);
        Msg userMsg = buildUserMessage(workspace, sessionId,
                (modePrefix.isEmpty() ? "" : modePrefix + "\n\n") + (message == null ? "" : message),
                attachments);
        startStream(agent, context, userMsg, sessionId, true, onEvent, onError, onFinish);
    }

    /**
     * 构建模式 + Skill 的 prompt 前缀（空字符串表示无附加）。
     * <p>Skill 查找顺序：工作区 .easyClaw/agent/skills/name.md → 全局 ~/.easyClaw/skills/name.md。
     * 找到则注入 frontmatter + 正文；找不到则静默跳过。</p>
     */
    private String buildModeSkillPrefix(WorkspaceContext workspace, ChatMode.BaseMode baseMode,
                                        String skillName) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatMode.buildInstruction(baseMode));
        if (skillName != null && !skillName.isBlank()) {
            String safeName = skillName.trim().endsWith(".md") ? skillName.trim() : skillName.trim() + ".md";
            Path workspaceSkill = workspace.getPath().resolve(".easyClaw/agent/skills").resolve(safeName);
            Path globalSkill = com.xinl.easyclaw.config.SystemHomePaths.globalSkillsDir().resolve(safeName);
            Path chosen = Files.exists(workspaceSkill) ? workspaceSkill
                    : Files.exists(globalSkill) ? globalSkill : null;
            if (chosen != null) {
                try {
                    String content = Files.readString(chosen, StandardCharsets.UTF_8);
                    sb.append("\n\n—— 已加载 Skill: ").append(skillName.trim()).append(" ——\n")
                            .append(content);
                    log.info("Skill 注入成功: name={}, path={}", skillName, chosen);
                } catch (IOException e) {
                    log.warn("读取 Skill 失败: name={}, err={}", skillName, e.getMessage());
                }
            } else {
                log.warn("Skill 文件不存在（跳过注入）: name={}, 查找路径: {}, {}", skillName, workspaceSkill, globalSkill);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 解析 baseMode：前端显式传入 > workspace 配置默认值 > PLAN。
     * workspace 级配置不区分 session，整个 workspace 共享。
     */
    private ChatMode.BaseMode resolveBaseMode(String workspaceId, ChatMode.BaseMode incoming) {
        if (incoming != null) return incoming;
        try {
            return configRepository
                    .findByWorkspaceIdAndConfigTypeAndConfigKey(workspaceId, "chat", "baseMode")
                    .map(e -> ChatMode.BaseMode.valueOf(e.getConfigValue()))
                    .orElse(ChatMode.BaseMode.PLAN);
        } catch (IllegalArgumentException ex) {
            return ChatMode.BaseMode.PLAN;
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
                // 已有路径（可能在 plan mode 中途被中断），保持原值不重置
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
     * 强制退出 Plan Mode（自由模式 QUICK 下调用）。
     * <p>清空 agent_state.json 中 PlanModeContextState 的 currentPlanFile，
     * 让框架 PlanModeManager.isInPlanMode() 返回 false，解除只读限制。</p>
     */
    private void exitPlanMode(WorkspaceContext workspace, String sessionId) {
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
            if (ctx == null) {
                return;
            }
            boolean wasActive = ctx.isPlanActive()
                    || (ctx.getCurrentPlanFile() != null && !ctx.getCurrentPlanFile().isBlank());
            if (wasActive) {
                ctx.setPlanActive(false);
                ctx.setCurrentPlanFile("");
                Files.writeString(stateFile, state.toJson(), StandardCharsets.UTF_8);
                log.info("强制退出 Plan Mode: session={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("退出 Plan Mode 失败（忽略）: {}", e.getMessage());
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

        // Plan Mode 按会话隔离（与 streamChat 保持一致）
        preconfigurePlanFile(workspace, sessionId);

        // 会话 → Workspace 映射（权限按 workspace 隔离）
        sessionWorkspaces.put(sessionId, workspaceId);

        List<ToolUseBlock> tools = pendingConfirms.remove(sessionId);
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
        Msg resume = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(approved ? "approved" : "denied")
                .metadata(meta)
                .build();

        log.info("用户{}了工具执行: workspaceId={}, sessionId={}, tools={}",
                approved ? "允许" : "拒绝", workspaceId, sessionId, tools == null ? 0 : tools.size());

        startStream(agent, context, resume, sessionId, false, onEvent, onError, onFinish);
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
                             boolean mainTurn,
                             Consumer<StreamEvent> onEvent,
                             Consumer<Throwable> onError,
                             Runnable onFinish) {
        final boolean[] ended = {false};
        final boolean[] retried = {false};
        final ToolTrace trace = new ToolTrace();
        final DeltaBatcher batcher = new DeltaBatcher(onEvent);

        Disposable disp = agent.streamEvents(msg, context)
                // 不设硬超时：LLM 思考/模型响应可能很久，事件流由 AgentScope 生命周期
                // （AGENT_END / 错误 / 用户中断）控制结束，避免长思考被误杀
                .doOnNext(event -> {
                    handleEvent(event, onEvent, onError, onFinish, trace, sessionId, agent, batcher);
                    // 收到 AGENT_END 即认为回复完成，立即复位 UI（不依赖 Flux complete）
                    if (event.getType() == AgentEventType.AGENT_END) {
                        ended[0] = true;
                        batcher.flush();
                        emitContextStatus(agent, onEvent);
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
                            startStream(agent, context, followUp, sessionId, false,
                                    onEvent, onError, onFinish);
                            return;
                        }
                        onFinish.run();
                    }
                })
                .doOnError(err -> {
                    log.error("流式对话执行失败: {}", err.getMessage(), err);
                    batcher.flush();
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
                    if (!ended[0]) {
                        try {
                            emitContextStatus(agent, onEvent);
                        } catch (Exception ignored) {
                            // 状态读取失败不影响复位
                        }
                        onFinish.run();
                    }
                })
                .subscribe();
        sessionDisposables.put(sessionId, disp);
    }

    /** 工具调用追溯状态（名称 / 参数 / 结果，按会话隔离） */
    private static final class ToolTrace {
        String toolName = "";
        boolean toolCalled = false;
        boolean hasText = false;
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
            case TOOL_CALL_START -> {
                if (event instanceof ToolCallStartEvent e) {
                    String name = e.getToolCallName();
                    trace.toolCalled = true;
                    trace.toolName = name;
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
                String result = trace.result.toString().trim();
                String state = event instanceof ToolResultEndEvent e
                        ? inferToolResultState(
                                e.getState() != null ? e.getState().name() : null,
                                result)
                        : inferToolResultState(null, result);
                onEvent.accept(StreamEvent.toolResult("(" + state + ") "
                        + (result.isEmpty() ? "(空结果)" : result)));
                onEvent.accept(StreamEvent.toolEnd(trace.toolName));
            }
            case REQUIRE_USER_CONFIRM -> {
                // 工具执行前需用户确认：已授权（回合/永久）的自动放行，其余弹窗
                if (event instanceof RequireUserConfirmEvent e) {
                    List<ToolUseBlock> tools = e.getToolCalls();
                    pendingConfirms.put(sessionId, tools);
                    List<ToolUseBlock> needConfirm = tools.stream()
                            .filter(t -> !isAllowed(sessionId, t.getName()))
                            .toList();
                    log.info("收到工具确认请求: session={}, tools={}, needConfirm={}",
                            sessionId,
                            tools.stream().map(ToolUseBlock::getName).toList(),
                            needConfirm.stream().map(ToolUseBlock::getName).toList());
                    if (needConfirm.isEmpty()) {
                        // 全部已授权（永久/回合）：仅发 autoConfirm 事件通知前端还不够——
                        // AgentScope 仍在等确认结果，必须真正提交 ConfirmResult 恢复 Agent，
                        // 否则会永远暂停（"保持 SSE 连接"但前端无提示）
                        onEvent.accept(StreamEvent.autoConfirm());
                        autoConfirmResume(sessionId, agent, tools, onEvent, onError, onFinish);
                    } else {
                        onEvent.accept(StreamEvent.confirm(buildConfirmJson(e.getReplyId(), needConfirm)));
                    }
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

    /**
     * 自动放行已授权工具：构造 ConfirmResult(true) 恢复消息并重新订阅 Agent 事件流，
     * 使 Agent 真正继续执行（否则 AgentScope 会一直停留在 ASKING 暂停状态）。
     * <p>关键点：先把 pendingConfirms 移除，避免新流的 doFinally 误判"暂停等待确认"而保持连接。</p>
     */
    private void autoConfirmResume(String sessionId, HarnessAgent agent, List<ToolUseBlock> tools,
                                   Consumer<StreamEvent> onEvent,
                                   Consumer<Throwable> onError,
                                   Runnable onFinish) {
        String workspaceId = sessionWorkspaces.get(sessionId);
        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            log.warn("自动放行失败：workspace 不存在, session={}", sessionId);
            return;
        }
        pendingConfirms.remove(sessionId);
        List<ConfirmResult> results = tools.stream().map(t -> new ConfirmResult(true, t)).toList();
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        Msg resume = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("approved")
                .metadata(meta)
                .build();
        log.info("自动放行已授权工具: session={}, tools={}",
                sessionId, tools.stream().map(ToolUseBlock::getName).toList());
        final boolean[] ended = {false};
        final ToolTrace t2 = new ToolTrace();
        final DeltaBatcher batcher2 = new DeltaBatcher(onEvent);
        try {
            Disposable disp = agent.streamEvents(resume, buildContext(workspace, sessionId))
                    .doOnNext(evt -> {
                        handleEvent(evt, onEvent, onError, onFinish, t2, sessionId, agent, batcher2);
                        if (evt.getType() == AgentEventType.AGENT_END) {
                            ended[0] = true;
                            batcher2.flush();
                            onFinish.run();
                        }
                    })
                    .doOnError(err -> {
                        log.warn("自动放行恢复失败: session={}, err={}", sessionId, err.getMessage());
                        batcher2.flush();
                        onError.accept(err);
                    })
                    .doFinally(signal -> {
                        batcher2.flush();
                        sessionDisposables.remove(sessionId);
                        // 若恢复后又暂停等确认（pendingConfirms 重新有值）：保持连接不 complete
                        if (pendingConfirms.containsKey(sessionId)) {
                            return;
                        }
                        if (!ended[0]) {
                            onFinish.run();
                        }
                    })
                    .subscribe();
            sessionDisposables.put(sessionId, disp);
        } catch (Exception e) {
            log.warn("自动放行恢复异常: session={}, err={}", sessionId, e.getMessage());
            onFinish.run();
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
        return RuntimeContext.builder()
                .userId(workspace.getUserId())
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
