package com.xinl.easyclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.agent.domain.ChatResponse;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.agent.orchestrator.OrchestrationAuditVerifier;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.AppConstants;
import com.xinl.easyclaw.config.RetryScope;
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
import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
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
    private final AgentScopeProperties agentScopeProperties;
    /** 会话级运行时状态（待确认工具、回合授权、订阅句柄、护栏计数……）统一由此持有 */
    private final SessionRegistry sessions;
    /** 激活场景查询（编排审计需要对照工作流计划） */
    private final com.xinl.easyclaw.workspace.ScenarioResolver scenarioResolver;

    /** 空闲会话 TTL：超过此时长无活动且无挂起确认的会话，其内存状态被清扫 */
    private static final long IDLE_TTL_MS = 2 * 60 * 60 * 1000L;

    /**
     * 用户停止后、强制 dispose 订阅前的宽限窗口。
     * <p>
     * 【为什么必须留这个窗口】AgentScope 中断路径上**唯一**把会话记忆写盘的地方是
     * {@code ReActAgent.handleInterrupt}（ReActAgent.java:4046 的 saveStateToSession），
     * 而它只在 {@code AgentBase.createErrorHandler}（AgentBase.java:497-499）捕获到
     * {@code InterruptedException} 时才会被调用。Reactor 的 cancel **不是 error**，
     * 提前 dispose 只会静默掐断订阅，异常永远不产生 → handleInterrupt 从不执行 →
     * 本轮的用户提问、工具结果、已输出文本全部烂在内存里，下一轮 activateSlotForContext
     * 又从 store 强制重载覆盖，于是「停止前做过的事彻底失忆」。
     * <p>
     * 因此这里先 interrupt 设标志，留一小段时间让 Agent 循环走到下一个
     * {@code checkInterrupted()} 检查点抛出中断异常并完成落盘，再兜底 dispose。
     */
    private static final long STOP_GRACE_MS = 2_000L;

    /** 停止宽限窗口的兜底 dispose 调度器（单线程即可，仅承载极短延时任务） */
    private final java.util.concurrent.ScheduledExecutorService stopGraceScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stop-grace-dispose");
                t.setDaemon(true);
                return t;
            });

    public AgentService(WorkspaceManager workspaceManager,
                        AgentFactory agentFactory,
                        PermissionRuleService permissionRuleService,
                        AgentScopeProperties agentScopeProperties,
                        SessionRegistry sessions,
                        com.xinl.easyclaw.workspace.ScenarioResolver scenarioResolver) {
        this.workspaceManager = workspaceManager;
        this.agentFactory = agentFactory;
        this.permissionRuleService = permissionRuleService;
        this.agentScopeProperties = agentScopeProperties;
        this.sessions = sessions;
        this.scenarioResolver = scenarioResolver;
    }

    /** 应用停机时关闭宽限调度器，避免守护线程与未决任务泄漏 */
    @jakarta.annotation.PreDestroy
    public void shutdownStopGraceScheduler() {
        stopGraceScheduler.shutdownNow();
    }

    /**
     * 本回合允许指定工具（不再弹窗确认）
     */
    public void allowTurn(String sessionId, Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        sessions.allowForTurn(sessionId, toolNames);
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
        for (String sessionId : sessions.sessionsOfWorkspace(workspaceId)) {
            syncPermissionRules(workspace.getAgent(), workspace, sessionId, workspaceId);
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
            allowed.addAll(sessions.turnAllowedTools(sessionId));

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
        List<ToolUseBlock> tools = sessions.peekPendingConfirm(sessionId);
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
     *   <li>中止本会话的 HTTP 重试退避</li>
     *   <li>摘除订阅句柄并清理本轮状态（**此时先不 dispose**）</li>
     *   <li>调用 agent.interrupt(userId, sessionId)（按会话槽精确设置中断标志，
     *       让 Agent 循环提前退出并释放 AgentBase 的 callGates 串行化闸门）</li>
     *   <li>{@link #STOP_GRACE_MS} 宽限期后兜底 dispose 订阅；若此时流仍未终止
     *       （工具阻塞在不可中断调用中），重建 Agent 释放被占死的 callGates</li>
     * </ol>
     * <p>
     * 【顺序不可调换】必须 interrupt 先于 dispose：中断路径上唯一的记忆落盘点
     * {@code handleInterrupt} 依赖 {@code InterruptedException} 被抛出，而 Reactor 的
     * cancel 不产生异常。先 dispose 会导致停止前的用户提问与已执行内容不进记忆。
     * 详见 {@link #STOP_GRACE_MS}。
     */
    public void stopChat(String workspaceId, String sessionId) {
        log.info("停止会话流: workspaceId={}, sessionId={}", workspaceId, sessionId);

        // 1. 仅中止本会话的 HTTP 重试（让正在等待退避的 retry 下次判断时直接放弃）；
        //    进程级 abortAll 会误伤其他会话，见 RetryScope 注释
        RetryScope.abort(sessionId);

        // 2. 摘除订阅句柄并清理本轮状态（保留 abort 标记，见方法末注释）
        //    【注意】此处**只摘不 dispose**：dispose 必须晚于下面的 interrupt，
        //    否则 Reactor 的 cancel 会静默掐断订阅、InterruptedException 永不产生，
        //    导致唯一的记忆落盘点 handleInterrupt 被跳过（见 STOP_GRACE_MS 注释）。
        Disposable disp = sessions.abortTurn(sessionId);

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

        // 4. 宽限窗口后兜底 dispose：给 Agent 循环留出走到下一个 checkInterrupted()
        //    检查点、抛出 InterruptedException 并在 handleInterrupt 里完成
        //    saveStateToSession 的时间。正常情况下中断异常会让流自行终止
        //    （doFinally 里已 clearDisposable），此时这里的 dispose 是幂等空操作。
        //    若 dispose 后流**仍未**终止，说明工具卡在不可中断的阻塞调用里，
        //    会顺带触发 recoverStuckAgent 重建 Agent，避免 callGates 被永久占用。
        scheduleGraceDispose(disp, sessionId, workspaceId);

        // 本轮状态已由上面的 sessions.abortTurn 一次性清理（待确认工具、回合授权、
        // 恢复标记、工具失败计数、确认超时倒计时与挂起回调）
        // 注意：此处**不能** RetryScope.clear(sessionId)——上面刚设置的 abort 标记需要保留，
        // 供正在退避等待的重试读取；标记由下一次 streamChat 或 releaseSession 清除
    }

    /**
     * 在 {@link #STOP_GRACE_MS} 宽限期后兜底 dispose 订阅。
     * <p>
     * 中断能正常传播时，流会自行 error 终止并走完 handleInterrupt 的落盘；
     * 只有中断没被 Agent 循环读到（例如卡在不可中断的阻塞调用里）时，
     * 才靠这里的延时 dispose 保证订阅不泄漏。
     */
    private void scheduleGraceDispose(Disposable disp, String sessionId, String workspaceId) {
        if (disp == null || disp.isDisposed()) {
            return;
        }
        try {
            stopGraceScheduler.schedule(() -> {
                if (disp.isDisposed()) {
                    log.debug("宽限期内流已自行终止（中断落盘已完成）: sessionId={}", sessionId);
                    return;
                }
                try {
                    disp.dispose();
                    log.info("宽限期到，兜底 dispose Flux 订阅: sessionId={}", sessionId);
                } catch (Exception e) {
                    log.warn("兜底 dispose 订阅异常: sessionId={}, err={}", sessionId, e.getMessage());
                }
                // 卡死判定与恢复：dispose 之后流仍未终止，说明取消没能传播到上游
                // ——工具正阻塞在不可打断的调用里（见 recoverStuckAgent 的完整说明）。
                // 此时 callGates 已被占死，必须重建 Agent，否则后续消息永久排队。
                if (!disp.isDisposed()) {
                    recoverStuckAgent(sessionId, workspaceId);
                }
            }, STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 调度器已关闭（应用停机）：退回同步 dispose，避免订阅泄漏
            log.warn("宽限 dispose 调度失败，改为立即 dispose: sessionId={}, err={}", sessionId, e.getMessage());
            try {
                disp.dispose();
            } catch (Exception ignore) {
                log.warn("立即 dispose 也失败: {}", ignore.getMessage());
            }
        }
    }

    /**
     * 从「工具卡死」状态恢复：重建 Agent，丢弃被占死的 callGates。
     * <p>
     * 【为什么需要这一步】工具执行在上游被
     * {@code Mono.fromCallable(...).subscribeOn(boundedElastic)} 包裹
     * （ToolExecutor.java:409-415），一旦进入 {@code call()} 就是不可打断的黑盒：
     * {@code Process.waitFor}、管道 {@code InputStream.read}、GraalPy 原生执行
     * 都对 {@code Thread.interrupt()} 免疫。而工具超时用的是 {@code Mono.timeout}
     * （ToolExecutor.java:426-429），只向下游发 error、向上游发 cancel，
     * **不会真正放弃已阻塞的工作线程**（且默认 30 分钟，对用户等同永久假死）。
     * <p>
     * 更关键的是 {@code ReActAgent} 全文只有一个中断检查点（ReActAgent.java:1729），
     * 调用点位于 {@code checkInterrupted().then(acting(iter))}（:2482，工具执行**之前**）
     * 和模型 chunk 上（:2549、:3601）——**工具执行内部没有任何检查点**。
     * 所以中断标志最早也要等「本轮所有工具都返回」才会被读到；工具不返回，
     * 中断就永远是死信。此时 {@code AgentBase} 按 slotKey 串行化的 callGates
     * 一直被占用，同一会话的下一条消息在 gate 上无声排队 ——
     * 用户看到的就是「已经点了停止，但后端毫无反应、也不往下进行」。
     * <p>
     * 重建 Agent 会替换掉持有这些 gate 的实例，新消息从 state 文件干净加载
     * （与流式错误后的自愈路径一致）。被卡住的旧工具线程仍会在后台空跑到自己结束，
     * 这是当前上游实现下无法避免的代价，但它不再阻塞用户。
     */
    private void recoverStuckAgent(String sessionId, String workspaceId) {
        String wid = workspaceId != null ? workspaceId : sessions.workspaceOf(sessionId);
        if (wid == null) {
            log.warn("检测到卡死流但无法定位 workspaceId，跳过 Agent 重建: sessionId={}", sessionId);
            return;
        }
        log.warn("停止后流仍未终止（工具阻塞在不可中断调用中），重建 Agent 以释放被占死的 callGates: "
                + "workspaceId={}, sessionId={}", wid, sessionId);
        try {
            workspaceManager.rebuildAgent(wid);
            log.info("卡死恢复完成，Agent 已重建: workspaceId={}", wid);
        } catch (Exception e) {
            log.error("卡死恢复失败，该会话后续消息可能仍无响应: workspaceId={}, err={}", wid, e.getMessage());
        }
    }

    /**
     * 用户主动介入当前轮次：把用户的插话作为 HintBlock 投进会话收件箱。
     * <p>
     * 与 {@link #stopChat} 的区别：**不中断**当前回合。消息进入
     * {@code agentscope:inbox:<sessionId>} 队列后，由 Harness 自动装配的
     * {@code InboxMiddleware}（HarnessAgent.java:2480）在**下一个推理步之前**排空，
     * 转成 HintBlock 注入当前上下文——模型因此能在本轮内立刻看到并响应用户的新指示，
     * 而不必等本轮结束、也不丢失已完成的工具结果。
     * <p>
     * payload 的键必须是 {@code id}/{@code hint}/{@code source}：
     * {@code InboxMiddleware.deserializeHintBlock}（:211-221）只认这三个键，
     * 且 {@code hint} 为空时整条消息会被**静默丢弃**。
     *
     * @return true 表示已成功投递；false 表示参数非法或该工作区尚无 Agent（无从注入）
     */
    public boolean interveneTurn(String workspaceId, String sessionId, String text) {
        if (workspaceId == null || workspaceId.isBlank()
                || sessionId == null || sessionId.isBlank()
                || text == null || text.isBlank()) {
            log.warn("介入参数非法: workspaceId={}, sessionId={}, textBlank={}",
                    workspaceId, sessionId, text == null || text.isBlank());
            return false;
        }

        MessageBus bus = workspaceManager.getMessageBus(workspaceId);
        if (bus == null) {
            log.warn("介入失败：工作区尚无 MessageBus（Agent 未构建）: workspaceId={}", workspaceId);
            return false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", UUID.randomUUID().toString().replace("-", ""));
        // 包裹标签让模型明确这是运行中用户的插话，优先级高于原指令
        payload.put("hint", "<user-intervention>" + text.trim() + "</user-intervention>");
        payload.put("source", "user");

        try {
            // block：介入是低频交互动作，需在返回前确认已落入队列，
            // 否则前端提示「已介入」但消息其实丢了，用户无从察觉
            bus.inboxPush(sessionId, payload).block(Duration.ofSeconds(5));
            log.info("介入消息已投递收件箱: sessionId={}, textLen={}", sessionId, text.trim().length());
            return true;
        } catch (Exception e) {
            log.warn("介入消息投递失败: sessionId={}, err={}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 统一会话状态驱逐入口：连接断开 / 会话删除 / 空闲超时都走这里。
     * <p>
     * 与 {@link #stopChat} 的区别：stopChat 表达「用户主动停止本轮」（需 interrupt Agent），
     * 本方法表达「该会话的内存状态不再需要」——释放订阅并清空全部会话级 Map 条目。
     * <p>
     * 必须清空 {@code turnAllowed} / {@code sessionWorkspaces}：残留的工具授权若被
     * 复用的 sessionId 继承，新会话会绕过确认弹窗（权限问题而非单纯内存问题）。
     */
    public void releaseSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Disposable disp = sessions.release(sessionId);
        if (disp != null && !disp.isDisposed()) {
            try {
                disp.dispose();
            } catch (Exception e) {
                // 释放失败不阻塞后续清理
                log.warn("releaseSession dispose 异常: sessionId={}, err={}", sessionId, e.getMessage());
            }
        }
    }

    /** 记录会话活跃时间（清扫任务据此判定空闲） */
    private void touchSession(String sessionId) {
        sessions.touch(sessionId);
    }

    /**
     * 保守释放：仅当会话既无活跃订阅、又无挂起确认时才驱逐。
     * <p>
     * 供「连接断开」这类**不代表用户意图终止**的场景调用：浏览器刷新/切网会触发断连，
     * 但后端回合仍在跑（前端重连后凭 pendingEvents 缓冲继续观看），此时若无条件
     * dispose 订阅会把正常回合杀掉。因此这里只回收确实已经空转的会话状态。
     *
     * @return true 表示已释放
     */
    public boolean releaseSessionIfIdle(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (sessions.hasPendingConfirm(sessionId)) {
            return false;
        }
        if (sessions.isRunning(sessionId)) {
            // 回合仍在执行：保留状态，交由 TTL 清扫或后续正常收尾处理
            return false;
        }
        releaseSession(sessionId);
        return true;
    }

    /**
     * 兜底清扫：驱逐空闲超过 {@link #IDLE_TTL_MS} 且无挂起确认、无活跃订阅的会话状态。
     * <p>
     * 正常路径（SSE 断开 / WS 断连 / 删除会话）已直接调用 {@link #releaseSession}，
     * 本任务只处理这些路径全部失效的漏网情况，保证 Map 不会无界增长。
     */
    @Scheduled(fixedDelay = 300_000L)
    void sweepIdleSessions() {
        long cutoff = System.currentTimeMillis() - IDLE_TTL_MS;
        List<String> expired = sessions.sessionsIdleBefore(cutoff).stream()
                // 挂起等待确认的会话不清（用户可能稍后回来确认）
                .filter(sid -> !sessions.hasPendingConfirm(sid))
                // 仍有活跃订阅说明流还在跑，不清
                .filter(sid -> !sessions.isRunning(sid))
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        log.info("清扫空闲会话内存状态: count={}", expired.size());
        expired.forEach(this::releaseSession);
    }

    /**
     * 工具确认超时清扫：用户永不点确认/拒绝（关页面、切走、误触）时，挂起回合的
     * {@code doFinally} 已 early return，不会触发 onFinish/onError —— 前端永久停留
     * 「思考中」，SSE 连接与会话 Map 条目常驻。本任务是该路径唯一的反向出口。
     */
    @Scheduled(fixedDelay = 60_000L)
    void sweepExpiredConfirmations() {
        long now = System.currentTimeMillis();
        List<String> expired = sessions.expiredConfirmations(now);
        for (String sessionId : expired) {
            // 先摘除 deadline：确保即使后续步骤抛异常也不会重复触发本分支
            sessions.clearConfirmDeadline(sessionId);
            if (!sessions.hasPendingConfirm(sessionId)) {
                // 用户已在本轮扫描间隙响应，无需取消
                sessions.takePendingFinisher(sessionId);
                sessions.takePendingEmitter(sessionId);
                continue;
            }
            log.warn("工具确认超时，自动取消本轮: sessionId={}", sessionId);
            try {
                Consumer<StreamEvent> emitter = sessions.takePendingEmitter(sessionId);
                if (emitter != null) {
                    emitter.accept(StreamEvent.error("工具确认超时，已自动取消本轮操作"));
                }
                Runnable finisher = sessions.takePendingFinisher(sessionId);
                if (finisher != null) {
                    finisher.run();
                }
            } catch (Exception ex) {
                log.warn("确认超时收尾失败: sessionId={}, err={}", sessionId, ex.toString());
            } finally {
                // 复用 stopChat 完成中止 + 状态清理，与用户手动点「停止」路径一致
                stopChat(sessions.workspaceOf(sessionId), sessionId);
                releaseSession(sessionId);
            }
        }
    }

    /**
     * 查询指定会话的运行状态（重连/刷新后前端用来恢复 UI 状态）。
     *
     * @return Map 包含 running(是否有活跃流)、pending(是否挂起等待确认)、pendingTools(待确认工具列表)
     */
    public Map<String, Object> getSessionStatus(String sessionId) {
        boolean running = sessions.isRunning(sessionId);
        List<Map<String, Object>> pendingTools = pendingConfirmInfo(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running);
        result.put("pending", !pendingTools.isEmpty());
        result.put("pendingTools", pendingTools);
        log.debug("查询会话状态: sessionId={}, running={}, pending={}", sessionId, running, !pendingTools.isEmpty());
        return result;
    }

    private boolean isAllowed(String sessionId, String toolName) {
        String workspaceId = sessions.workspaceOf(sessionId);
        if (workspaceId != null && permissionRuleService.isAlwaysAllowed(workspaceId, toolName)) {
            return true;
        }
        return sessions.isAllowedThisTurn(sessionId, toolName);
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

        // 清除本会话上次 stopChat 设置的 abort 标志（允许新回合重试）
        RetryScope.clear(sessionId);

        WorkspaceContext workspace = workspaceManager.getWorkspace(workspaceId);
        if (workspace == null) {
            onError.accept(new RuntimeException("❌ 工作区未找到: " + workspaceId));
            return;
        }

        // 会话 → Workspace 映射（权限按 workspace 隔离）
        sessions.bindWorkspace(sessionId, workspaceId);
        touchSession(sessionId);

        // 新一轮用户消息：重置子 Agent 调度计数与工具失败计数（循环防护基准），
        // 并清除「确认恢复」标记，恢复常规 end 逻辑
        sessions.beginTurn(sessionId);

        // 清理上一轮遗留的挂起确认（刷新/离开页面导致的 ASKING 状态），
        // 避免新消息直接抛 "Agent is paused for human-in-the-loop confirmation"
        // 【单次读写】三项状态预处理（清挂起确认 / 净化污染 / 预设 plan 路径）合并为
        // 一次 agent_state.json 读 + 最多一次写。此前是各自独立读写，长会话状态文件
        // 可达数百 KB，每轮 3 次全量读 + 反序列化直接体现为「发消息后先卡一下」。
        boolean needRebuild = prepareSessionState(workspace, sessionId);
        if (needRebuild) {
            // 清理过挂起确认：重建 Agent 使其从清理后的状态文件加载（不阻塞、毫秒级）
            workspaceManager.rebuildAgent(workspaceId);
            workspace = workspaceManager.getWorkspace(workspaceId);
        }
        HarnessAgent agent = workspace.getAgent();
        RuntimeContext context = buildContext(workspace, sessionId);

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
            String wid = sessions.workspaceOf(sessionId);
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
                    byte[] raw = Base64.getDecoder().decode(att.base64Data());
                    String mime = resolveImageMime(att.mimeType(), att.name(), raw);
                    if (mime != null) {
                        blocks.add(ImageBlock.builder()
                                .source(new Base64Source(mime, att.base64Data()))
                                .build());
                    } else {
                        String text = new String(raw, StandardCharsets.UTF_8);
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
     * 判定附件是否为图片并返回规范化 MIME；非图片返回 {@code null}。
     * <p>
     * 三级判定：声明的 mimeType → 文件魔数 → 扩展名。之所以不只信 mimeType，
     * 是因为浏览器在 Windows 缺少注册表项时 {@code File.type} 会是空串，
     * 空 mimeType 会让图片落入文本分支被 UTF-8 强解成乱码（「图片传输损坏」）。
     * 魔数是唯一不依赖客户端诚信的判据，故优先级高于扩展名。
     */
    static String resolveImageMime(String declared, String name, byte[] data) {
        if (declared != null && declared.startsWith("image/")) {
            return declared;
        }
        String sniffed = sniffImageMime(data);
        if (sniffed != null) {
            return sniffed;
        }
        String ext = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> null;
        };
    }

    /** 按魔数嗅探图片类型；无法识别返回 {@code null}。 */
    private static String sniffImageMime(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "image/gif";
        }
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        if (d[0] == 'B' && d[1] == 'M') {
            return "image/bmp";
        }
        return null;
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
     * 会话状态预处理：一次读取 agent_state.json，串行应用三项清理/预设，最多回写一次。
     * <p>
     * 此前三项各自独立读文件 + 反序列化 + 回写（{@code clearStaleConfirmation} /
     * {@code cleanupPollutedContext} / {@code preconfigurePlanFile}），长会话下
     * agent_state.json 可达数百 KB，每轮 3 次全量解析同步阻塞在发送线程上，
     * 表现为「每次输入都要挂起一会儿才响应」。合并后 I/O 与解析各降至 1 次。
     *
     * @return true 表示上下文被修改过（调用方应 rebuildAgent 以加载清理后的状态）
     */
    private boolean prepareSessionState(WorkspaceContext workspace, String sessionId) {
        Path stateFile = sessionStateDir(workspace, sessionId).resolve("agent_state.json");
        if (!Files.exists(stateFile)) {
            return false;
        }
        try {
            AgentState state = AgentState.fromJsonString(Files.readString(stateFile));
            // 注意：两项清理必须都执行，不能用 || 短路——残留的 ASKING 消息与被污染的
            // 上下文是两个独立问题，可能同时存在
            boolean askingRemoved = removeAskingMessages(state, sessionId);
            boolean polluteRemoved = purgePollutedContext(state, sessionId);
            // 上下文类改动需要重建 Agent；plan 路径只是预设字段，回写即可，无需重建
            boolean contextChanged = askingRemoved || polluteRemoved;
            boolean planChanged = applyPlanFile(state, sessionId);
            if (contextChanged || planChanged) {
                Files.writeString(stateFile, state.toJson(), StandardCharsets.UTF_8);
            }
            return contextChanged;
        } catch (Exception e) {
            log.warn("会话状态预处理失败（忽略）: session={}, {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 移除含 ASKING 工具调用的消息（保留其余历史）。
     * <p>用户在确认弹窗出现时刷新/离开页面后，暂停状态会被持久化到 agent_state.json，
     * 此时直接发新消息会抛
     * {@code IllegalStateException: Agent is paused for human-in-the-loop confirmation}。</p>
     *
     * @return true 表示确实移除过消息
     */
    private boolean removeAskingMessages(AgentState state, String sessionId) {
        if (state.getContext() == null) {
            return false;
        }
        List<Msg> mutable = state.contextMutable();
        int before = mutable.size();
        mutable.removeIf(m -> m.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(t -> t.getState() == ToolCallState.ASKING));
        int removed = before - mutable.size();
        if (removed == 0) {
            return false;
        }
        log.info("已清除挂起工具确认（移除 ASKING 消息 {}/{}）: session={}", removed, before, sessionId);
        return true;
    }

    /**
     * 清理上下文污染：
     * 1. 孤儿 ToolResultBlock（有 tool_call_id 但前面没有 assistant ToolUseBlock 声明过）
     * 2. 空 user 消息（content="" 且没有图片/附件，是 autoConfirmResume 注入的空消息）
     * 3. 悬空 ToolUseBlock（assistant 声明了 tool_call 但全程没有对应 ToolResultBlock）
     * 这三种污染都会导致 OpenAI-compatible API（方舟）报 InvalidParameter，
     * 因为方舟严格校验 tool_call 链的配对关系。
     * <p>
     * 第 3 种是「用户在工具执行中点停止」的典型残留：中断发生在 TOOL_RESULT 落盘之前，
     * 消息里留下一个永远等不到结果的 tool_call。它的 state 是 RUNNING 而非 ASKING，
     * 因此 {@link #removeAskingMessages} 抓不到。此时下一条消息会在模型侧直接被拒，
     * 且失败发生在流建立之前 → 前端收不到任何事件，表现为「点停止后再发消息就一直挂起」。
     * <p>
     * 对悬空 tool_call 采取「补一条 interrupted 结果」而非删除声明：保留了现场语义
     * （模型能看到该工具被用户中断），也维持了 tool_call 链的配对完整性。
     */
    private boolean purgePollutedContext(AgentState state, String sessionId) {
        if (state.getContext() == null) {
            return false;
        }
        try {
            List<Msg> original = state.getContext();
            // 两个集合都基于 original 全量扫描（后续补配对遍历的是 cleaned）。
            // 这样混用是安全的：孤儿清理只会删除「id 不在 declaredToolIds 中」的结果块，
            // 而补配对只遍历 ToolUseBlock（其 id 必然在 declaredToolIds 中），
            // 两者作用的 id 集合天然不相交，不会出现「结果被删掉却仍被视为已配对」。
            Set<String> declaredToolIds = new HashSet<>();
            Set<String> resolvedToolIds = new HashSet<>();
            for (Msg m : original) {
                for (ToolUseBlock tub : m.getContentBlocks(ToolUseBlock.class)) {
                    if (tub.getId() != null) {
                        declaredToolIds.add(tub.getId());
                    }
                }
                for (ToolResultBlock trb : m.getContentBlocks(ToolResultBlock.class)) {
                    if (trb.getId() != null) {
                        resolvedToolIds.add(trb.getId());
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
            // 悬空 tool_call 补配对：为「声明了但没有结果」的 ToolUseBlock 追加一条
            // interrupted 结果消息，紧跟在声明它的消息之后，保证 tool_call 链配对完整。
            int danglingToolCalls = 0;
            if (!declaredToolIds.isEmpty() && !resolvedToolIds.containsAll(declaredToolIds)) {
                List<Msg> paired = new ArrayList<>(cleaned.size());
                for (Msg m : cleaned) {
                    paired.add(m);
                    for (ToolUseBlock tub : m.getContentBlocks(ToolUseBlock.class)) {
                        if (tub.getId() != null && !resolvedToolIds.contains(tub.getId())) {
                            // 每个悬空 tool_call 必须单独一条 TOOL 消息：
                            // OpenAIMessageConverter.convertToolMessage 用
                            // getFirstContentBlock(ToolResultBlock.class) 取值，一条消息里
                            // 塞多个结果块只有第一个会被发出，其余会被静默丢弃 → 仍然悬空
                            paired.add(Msg.builder()
                                    .role(MsgRole.TOOL)
                                    .content(ToolResultBlock.of(tub.getId(), tub.getName(),
                                            TextBlock.builder()
                                                    .text("⚠️ 该工具调用已被用户中断，未执行完成。")
                                                    .build()))
                                    .build());
                            resolvedToolIds.add(tub.getId());
                            danglingToolCalls++;
                        }
                    }
                }
                cleaned = paired;
            }
            if (orphanToolResults == 0 && emptyUserMsgs == 0 && danglingToolCalls == 0) {
                return false;
            }
            state.contextMutable().clear();
            state.contextMutable().addAll(cleaned);
            log.info("上下文净化: 移除孤儿 ToolResultBlock={}, 空 user 消息={}, 补配对悬空 tool_call={}, 剩余消息 {}/{}: session={}",
                    orphanToolResults, emptyUserMsgs, danglingToolCalls, cleaned.size(), original.size(), sessionId);
            return true;
        } catch (Exception e) {
            log.warn("上下文净化失败（忽略）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Plan Mode 按会话隔离：预设 PlanModeContextState.currentPlanFile
     * 为 {@code plans/{sessionId}/PLAN.md}（仅改内存状态，由调用方统一回写）。
     * <p>AgentScope 框架的 PlanModeManager.enter() 只会在 currentPlanFile 为空时，
     * 才回退到默认的 {@code planDir + "/PLAN.md"}（由 Builder.planFileDirectory 配置）。
     * 提前按 session 注入路径，即可让不同会话的方案文件各自独立，互不覆盖。</p>
     *
     * @return true 表示写入了新的 plan 路径（需要回写状态文件）
     */
    private boolean applyPlanFile(AgentState state, String sessionId) {
        PlanModeContextState ctx = state.getPlanModeContext();
        // 已有路径：保持不变（避免每轮无谓回写整个状态文件）
        if (ctx == null || (ctx.getCurrentPlanFile() != null && !ctx.getCurrentPlanFile().isBlank())) {
            return false;
        }
        String planFile = "plans/" + sessionId + "/PLAN.md";
        ctx.setCurrentPlanFile(planFile);
        log.debug("预设 Plan 文件路径: session={}, planFile={}", sessionId, planFile);
        return true;
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

        // 会话 → Workspace 映射（权限按 workspace 隔离）
        sessions.bindWorkspace(sessionId, workspaceId);
        touchSession(sessionId);
        // 标记进入「确认恢复」阶段：恢复流统一负责发送 end，原暂停流不再发送
        sessions.markResuming(sessionId);

        List<ToolUseBlock> tools = sessions.takePendingConfirm(sessionId);
        // 用户已响应：撤销确认超时倒计时与挂起收尾回调，避免清扫任务误取消已恢复的回合
        sessions.disarmConfirmTimeout(sessionId);
        // 恢复执行等同新回合：清除可能残留的 abort 标记，否则续跑的请求一遇 429 就直接放弃
        RetryScope.clear(sessionId);
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
        private final Map<String, StringBuilder> subagentThinkBufs = new HashMap<>();
        private long lastEmitAt = 0;
        private final Consumer<StreamEvent> emitter;

        DeltaBatcher(Consumer<StreamEvent> emitter) {
            this.emitter = emitter;
        }

        void onText(String delta) {
            if (idle()) {
                emitter.accept(StreamEvent.text(delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                textBuf.append(delta);
                tryBurstFlush();
            }
        }

        void onReasoning(String delta) {
            if (idle()) {
                emitter.accept(StreamEvent.reasoning(delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                thinkBuf.append(delta);
                tryBurstFlush();
            }
        }

        void onSubagentText(String subName, String delta) {
            if (idle()) {
                emitter.accept(StreamEvent.subagentText(subName, delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                subagentBufs.computeIfAbsent(subName, k -> new StringBuilder()).append(delta);
                tryBurstFlush();
            }
        }

        void onSubagentReasoning(String subName, String delta) {
            if (idle()) {
                emitter.accept(StreamEvent.subagentReasoning(subName, delta));
                lastEmitAt = System.currentTimeMillis();
            } else {
                subagentThinkBufs.computeIfAbsent(subName, k -> new StringBuilder()).append(delta);
                tryBurstFlush();
            }
        }

        /** 所有缓冲区都为空时可直接透传，无需担心乱序 */
        private boolean idle() {
            return textBuf.length() == 0 && thinkBuf.length() == 0
                    && subagentBufs.isEmpty() && subagentThinkBufs.isEmpty();
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
            if (!subagentThinkBufs.isEmpty()) {
                for (Map.Entry<String, StringBuilder> e : subagentThinkBufs.entrySet()) {
                    if (e.getValue().length() > 0) {
                        emitter.accept(StreamEvent.subagentReasoning(e.getKey(), e.getValue().toString()));
                    }
                }
                subagentThinkBufs.clear();
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
            for (StringBuilder sb : subagentThinkBufs.values()) {
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
        startStream(agent, context, msg, sessionId, mainTurn, isResumeStream, false,
                onEvent, onError, onFinish);
    }

    /**
     * @param forceFinish 本流必须收尾：绕过 {@link #finishTurn} 的 {@code isResuming} 抑制闸门。
     *                    仅由 NO_REPLY 续问流传 true —— 父流已放弃发 end（把责任交给续问流），
     *                    若续问流又被闸门静默吞掉，则两边都不发 end，前端永久停留「正在输出」。
     */
    private void startStream(HarnessAgent agent, RuntimeContext context, Msg msg, String sessionId,
                             boolean mainTurn, boolean isResumeStream, boolean forceFinish,
                             Consumer<StreamEvent> onEvent,
                             Consumer<Throwable> onError,
                             Runnable onFinish) {
        debugLogContext(agent, sessionId);
        final boolean[] ended = {false};
        final boolean[] retried = {false};
        // NO_REPLY 续问标记：绝不能在 doOnNext 内递归 startStream —— 子流开头会 dispose
        // “上一轮”订阅，而那正是当前仍在执行回调的父流自己；父流随后的 doFinally 又会
        // remove 掉子流刚登记的句柄，使续问流成为孤儿流（stopChat 取不到 Disposable，
        // 停止按钮失效）。因此这里只置标记，真正发起放到流终止后的单一决策点。
        final boolean[] needFollowUp = {false};
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
        Disposable prev = sessions.takeDisposable(sessionId);
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
                        // 编排审计（P1）：team 场景下比对「计划阶段」与主智能体自报的实际执行
                        if (mainTurn) {
                            emitOrchestrationAudit(sessionId, trace, eventSink);
                        }
                        // NO_REPLY 兜底：主回合调用了工具但**完全没有产出**（正文与思考皆空，
                        // 例如模型输出 NO_REPLY 被 MemoryFlush 压缩询问吞掉）→ 标记待续问。
                        // 判据用 hasOutput 而非 hasText：thinking-only 回合 UI 已有内容，不算无回复。
                        if (mainTurn && trace.toolCalled && !trace.hasOutput && !retried[0]) {
                            retried[0] = true;
                            needFollowUp[0] = true;
                            log.info("主回合调用了工具但无文本回复（NO_REPLY?），待流终止后自动续问总结: session={}", sessionId);
                            // 本回合不收尾：end 由续问流负责发送
                            return;
                        }
                        finishTurn(sessionId, isResumeStream, forceFinish, onFinish);
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
                        String wid = sessions.workspaceOf(sessionId);
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
                    sessions.clearDisposable(sessionId);
                    // 流终止（complete/error/cancel）兜底：确保 UI 一定复位，状态最终落盘
                    if (mainTurn) {
                        // 主回合结束：清空"本回合允许"授权，避免残留导致后续写工具不再弹窗
                        sessions.endTurn(sessionId);
                    }
                    // Agent 暂停等待用户确认（pendingConfirms 未清）：此时事件流会 complete，
                    // 但绝不能 complete 掉 SSE 连接 —— 否则 confirm 弹窗后的 resume 事件无处推送。
                    // 保持连接，待 resumeChat 清空 pendingConfirms 后由 resume 的流正常收尾。
                    if (sessions.hasPendingConfirm(sessionId)) {
                        log.info("Agent 暂停等待确认，保持 SSE 连接: sessionId={}", sessionId);
                        // 登记收尾回调：确认迟迟不来时由 sweepExpiredConfirmations 调用它复位
                        // 前端并关闭 SSE —— 这是本路径唯一的反向出口
                        sessions.registerPendingCallbacks(sessionId, onFinish, onEvent);
                        return;
                    }
                    // 流终止兜底（被新消息顶替 dispose / 用户停止 / 异常取消）：
                    // 在途工具补发收尾事件，避免 UI 工具卡片永久停留在"执行中"
                    closeInFlightTool(trace, eventSink, "已中断");
                    if (recorder != null) {
                        recorder.flushAll();
                    }
                    // 单一决策点：父流已终止且句柄已清理，此时发起续问流是安全的
                    // （子流的 sessionDisposables.put 不会再被父流 remove 覆盖）
                    if (needFollowUp[0]) {
                        Msg followUp = Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .textContent("（系统提示）请基于刚才的工具执行结果，直接给用户完整、清晰的中文总结回复。"
                                        + "不要输出 NO_REPLY，不要重复工具调用，直接总结。")
                                .build();
                        log.info("发起 NO_REPLY 续问流: sessionId={}", sessionId);
                        // 续问流继承 isResumeStream，保证 end 抑制语义与父流一致；
                        // forceFinish=true：父流已放弃收尾，续问流必须发出 end（见方法注释）
                        startStream(agent, context, followUp, sessionId, false, isResumeStream, true,
                                onEvent, onError, onFinish);
                        return;
                    }
                    if (!ended[0]) {
                        try {
                            emitContextStatus(agent, eventSink);
                        } catch (Exception ignored) {
                            // 状态读取失败不影响复位
                        }
                        finishTurn(sessionId, isResumeStream, forceFinish, onFinish);
                    }
                })
                // 向下游 transport 传递 sessionId：RetryableHttpTransport 据此判断是否放弃重试
                // （Reactor Context 是反应式链路中唯一可靠的会话标识传递方式）
                .contextWrite(ctx -> ctx.put(RetryScope.CONTEXT_KEY, sessionId))
                .subscribe();
        sessions.setDisposable(sessionId, disp);
    }

    /**
     * 流终止时为所有在途工具（TOOL_CALL_START 后未收到 TOOL_RESULT_END）补发收尾事件，
     * 避免 UI 工具卡片永久停留在"执行中"。
     * <p>
     * 并发调用下可能同时有多个在途条目，必须逐个收尾：早期实现只收尾「最后一次」调用，
     * 其余卡片仍会永久转圈。幂等：收尾后清空集合，重复调用无副作用。
     */
    private void closeInFlightTool(ToolTrace trace, Consumer<StreamEvent> sink, String note) {
        if (trace == null || trace.calls.isEmpty()) {
            return;
        }
        // 先取快照再清空：收尾过程中若 sink 回调再次触发本方法，不会重复发送
        List<Map.Entry<String, ToolCallSlot>> pending = new ArrayList<>(trace.calls.entrySet());
        trace.calls.clear();
        for (Map.Entry<String, ToolCallSlot> entry : pending) {
            ToolCallSlot st = entry.getValue();
            if (st == null || !st.inFlight || st.toolName == null || st.toolName.isEmpty()) {
                continue;
            }
            st.inFlight = false;
            // 兜底 key 是内部占位符，不能当作真实 toolCallId 下发
            String callId = "__no_id__".equals(entry.getKey()) ? null : entry.getKey();
            try {
                sink.accept(StreamEvent.toolResult("(" + note + ")", callId));
                sink.accept(StreamEvent.toolEnd(st.toolName, callId));
            } catch (Exception ignored) {
                // 收尾失败不影响主流程
            }
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
        finishTurn(sessionId, isResumeStream, false, onFinish);
    }

    /**
     * @param forceFinish 绕过 resuming 抑制闸门，保证 end 一定送达（见 NO_REPLY 续问流）。
     */
    private void finishTurn(String sessionId, boolean isResumeStream, boolean forceFinish,
                            Runnable onFinish) {
        boolean isResuming = sessions.isResuming(sessionId);
        if (!forceFinish && !isResumeStream && isResuming) {
            log.debug("抑制旧流(end)：确认恢复流将统一发送, sessionId={}", sessionId);
            return;
        }
        onFinish.run();
        if (isResumeStream) {
            sessions.clearResuming(sessionId);
        }
    }

    /** 单次工具调用的追溯状态（名称 / 参数 / 结果 / 在途标记） */
    private static final class ToolCallSlot {
        final String toolName;
        final StringBuilder args = new StringBuilder();
        final StringBuilder result = new StringBuilder();
        /** 在途标记：TOOL_CALL_START 后、TOOL_RESULT_END 前为 true，用于流终止时补发收尾 */
        boolean inFlight = true;

        ToolCallSlot(String toolName) {
            this.toolName = toolName == null ? "" : toolName;
        }
    }

    /**
     * 工具调用追溯状态（按会话隔离）。
     * <p>
     * 早期实现是「单槽」的（只保存最后一次调用的名称/入参/结果）。并发工具调用下，
     * 后发起的 TOOL_CALL_START 会直接覆盖前一次的槽位，于是先发起的那次调用永远等不到
     * 属于它的 tool_end —— UI 卡片永久停留在「执行中」。这里改为按 toolCallId 索引的多槽，
     * 每次调用各自独立累积；TOOL_RESULT_END 后移除条目，因此残留条目恰好就是「在途」集合。
     */
    private static final class ToolTrace {
        boolean toolCalled = false;
        boolean hasText = false;
        /**
         * 本回合是否有任何面向用户的产出（正文 或 思考）。
         * <p>
         * 与 {@link #hasText} 分开：某些模型（或被冗长工具结果带偏时）会把整段正文写进
         * thinking 通道，正文通道全空。此时 UI 其实已有内容展示，绝不能判为「无回复」。
         * NO_REPLY 兜底分支是不发 end 的 —— 误判会导致前端永久停留「正在输出」。
         */
        boolean hasOutput = false;
        /** toolCallId → 该次调用的状态；LinkedHashMap 保证补发收尾时按发起顺序 */
        final Map<String, ToolCallSlot> calls = new LinkedHashMap<>();
        /**
         * 主智能体最终回复文本（仅用于编排审计比对，容量上限见 {@link #appendReply}）。
         * team 模式下需要从回复中提取 &lt;orchestration-audit&gt; 标记。
         */
        final StringBuilder reply = new StringBuilder();

        /**
         * 事件携带的 toolCallId 为空时的兜底 key。
         * <p>
         * 退化为「最后一次在途调用」，等价于旧的单槽行为：并发场景可能配错，
         * 但不带 id 的事件源本来也无法区分，至少保证卡片能被关闭而不是永久转圈。
         */
        String resolveKey(String toolCallId) {
            if (toolCallId != null && !toolCallId.isEmpty()) {
                return toolCallId;
            }
            String fallback = null;
            for (Map.Entry<String, ToolCallSlot> e : calls.entrySet()) {
                if (e.getValue().inFlight) {
                    fallback = e.getKey();
                }
            }
            return fallback != null ? fallback : "__no_id__";
        }

        ToolCallSlot get(String toolCallId) {
            return calls.get(resolveKey(toolCallId));
        }

        /**
         * TOOL_CALL_START 专用 key：id 缺失时不能复用「最后一次在途」的槽位
         * （那会把新调用写进旧卡片），固定落到单槽兜底位。
         */
        String resolveKeyForStart(String toolCallId) {
            return (toolCallId != null && !toolCallId.isEmpty()) ? toolCallId : "__no_id__";
        }

        /** 追加回复文本；超过上限后停止累积，避免长回复占用内存（审计标记在尾部，改用滑动保留尾部） */
        void appendReply(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            reply.append(delta);
            int max = 8192;
            if (reply.length() > max) {
                reply.delete(0, reply.length() - max);
            }
        }
    }

    /**
     * 子 Agent 事件隔离路由：其文本 / 思考 / 工具调用 / 结果全部进入
     * 子 Agent 自己的折叠块（subagent_text 事件），绝不混入主流程。
     * TEXT/THINKING delta 通过 batcher 合并；工具调用/结果等非 delta 事件立即 flush 后发送。
     */
    private void handleSubagentEvent(AgentEvent event, String subName, Consumer<StreamEvent> onEvent,
                                     DeltaBatcher batcher, String sessionId) {
        String source = event.getSource();
        // 缓冲 key 带上 sessionId：原先仅用 source，跨会话同名子 Agent 会互相串写，
        // 且会话结束后无法按会话前缀清理残留条目
        String bufKey = sessionId + "::" + source;
        if (event instanceof TextBlockDeltaEvent e) {
            batcher.onSubagentText(subName, e.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            batcher.onSubagentReasoning(subName, e.getDelta());
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
                sessions.clearSubagentResult(bufKey);
                onEvent.accept(StreamEvent.subagentTool(subName, e.getToolCallName()));
            } else if (event instanceof ToolCallDeltaEvent e) {
                onEvent.accept(StreamEvent.subagentToolArgs(subName, e.getDelta()));
            } else if (event instanceof ToolResultTextDeltaEvent e) {
                // 结果文本只累积不逐字外发：等 ToolResultEndEvent 带状态一次性下发，
                // 避免工具原始输出混进子 Agent 的正文时间线。
                // 累积上限见 SessionRegistry.MAX_SUBAGENT_BUFFER_CHARS：超限后停止追加，
                // 避免超长工具输出（如全量日志）撑爆内存。
                sessions.appendSubagentResult(bufKey, e.getDelta());
            } else if (event instanceof ToolResultEndEvent e) {
                String result = sessions.takeSubagentResult(bufKey);
                String state = inferToolResultState(
                        e.getState() != null ? e.getState().name() : null,
                        result);
                onEvent.accept(StreamEvent.subagentToolResult(subName, state, result));
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
    /**
     * 写类工具（会改动工作区文件的工具）名单。
     * <p>
     * 只列出「确定会写文件」的工具：{@code execute} 虽然也可能改文件，但无法从命令行
     * 可靠解析出受影响路径，误报会导致前端无意义地反复重拉，故不纳入。
     */
    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file");

    /**
     * 写类工具成功后推送 file_changed 事件，驱动前端实时刷新。
     * <p>
     * 路径从工具入参 JSON 的 {@code path} 字段取。入参可能因流式累积而不是合法 JSON
     * （见 ToolCallsAccumulator 的静默降级行为），此时静默跳过——刷新是增强能力，
     * 不能因为解析失败影响主对话流程。
     */
    private static void emitFileChangedIfWriteTool(String toolName,
                                                   String argsJson,
                                                   Consumer<StreamEvent> onEvent) {
        if (toolName == null || !FILE_WRITE_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (argsJson == null || argsJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(argsJson);
            String path = node.path("path").asText("");
            if (path.isBlank()) {
                return;
            }
            onEvent.accept(StreamEvent.fileChanged(path.replace('\\', '/')));
        } catch (Exception e) {
            log.debug("file_changed 事件跳过（工具入参非合法 JSON）: tool={}", toolName);
        }
    }

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
            handleSubagentEvent(event, source.substring(source.lastIndexOf('/') + 1), onEvent, batcher, sessionId);
            return;
        }
        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> {
                if (event instanceof TextBlockDeltaEvent e) {
                    trace.hasText = true;
                    trace.hasOutput = true;
                    trace.appendReply(e.getDelta());
                    batcher.onText(e.getDelta());
                }
            }
            case THINKING_BLOCK_DELTA -> {
                if (event instanceof ThinkingBlockDeltaEvent e) {
                    // 思考内容同样算「本回合有产出」，理由见 ToolTrace#hasOutput
                    trace.hasOutput = true;
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
                    String callId = e.getToolCallId();
                    trace.toolCalled = true;
                    trace.calls.put(trace.resolveKeyForStart(callId), new ToolCallSlot(name));
                    if (name != null && name.toLowerCase().contains("subagent")) {
                        String subName = extractSubagentName(name);
                        onEvent.accept(StreamEvent.subagent(subName));
                        // 循环调度防护：同一会话内同一子 Agent 超过配置次数 → 打断
                        int maxSameSubagent = agentScopeProperties.getAgent().getMaxSameSubagentCalls();
                        int count = sessions.recordSubagentCall(sessionId, subName);
                        if (maxSameSubagent > 0 && count > maxSameSubagent) {
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
                        onEvent.accept(StreamEvent.tool(name, callId));
                    }
                }
            }
            case TOOL_CALL_DELTA -> {
                if (event instanceof ToolCallDeltaEvent e) {
                    ToolCallSlot st = trace.get(e.getToolCallId());
                    if (st != null) {
                        st.args.append(e.getDelta());
                    }
                }
            }
            case TOOL_CALL_END -> {
                String callId = event instanceof ToolCallEndEvent e ? e.getToolCallId() : null;
                ToolCallSlot st = trace.get(callId);
                String args = st == null ? "" : st.args.toString().trim();
                onEvent.accept(StreamEvent.toolArgs(args.isEmpty() ? "(无参数)" : args, callId));
            }
            case TOOL_RESULT_START -> {
                if (event instanceof ToolResultStartEvent e) {
                    ToolCallSlot st = trace.get(e.getToolCallId());
                    if (st != null) {
                        st.result.setLength(0);
                    }
                }
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                if (event instanceof ToolResultTextDeltaEvent e) {
                    ToolCallSlot st = trace.get(e.getToolCallId());
                    if (st != null) {
                        st.result.append(e.getDelta());
                    }
                }
            }
            case TOOL_RESULT_END -> {
                String callId = event instanceof ToolResultEndEvent re ? re.getToolCallId() : null;
                String key = trace.resolveKey(callId);
                ToolCallSlot st = trace.calls.get(key);
                // 该次调用已收尾 → 从在途集合移除（残留条目即「在途」，供流终止时补发）
                trace.calls.remove(key);
                String toolName = st != null ? st.toolName : "";
                String result = st == null ? "" : st.result.toString().trim();
                String state = event instanceof ToolResultEndEvent e
                        ? inferToolResultState(
                                e.getState() != null ? e.getState().name() : null,
                                result)
                        : inferToolResultState(null, result);
                onEvent.accept(StreamEvent.toolResult("(" + state + ") "
                        + (result.isEmpty() ? "(空结果)" : result), callId));
                // 工具连续失败护栏：同一工具连续失败达到配置阈值时注入停止提示
                if ("ERROR".equalsIgnoreCase(state)) {
                    int maxFails = agentScopeProperties.getAgent().getMaxConsecutiveToolFailures();
                    int fails = sessions.recordToolFailure(sessionId, toolName);
                    if (maxFails > 0 && fails >= maxFails) {
                        log.warn("工具连续失败护栏触发: session={}, tool={}, fails={}",
                                sessionId, toolName, fails);
                        onEvent.accept(StreamEvent.context(
                                "{\"type\":\"tool_fail_guard\",\"tool\":\"" + toolName
                                + "\",\"fails\":" + fails
                                + ",\"message\":\"工具[" + toolName + "] 连续失败 " + fails
                                + " 次，请停止重试，检查参数/路径/权限后向用户说明并询问。\"}"));
                    }
                } else {
                    // 成功则重置该工具的失败计数
                    sessions.resetToolFailure(sessionId, toolName);
                    // 写类工具成功 → 通知前端刷新文件树/已打开预览（实时刷新，免手动点「刷新」）
                    emitFileChangedIfWriteTool(toolName, st == null ? "" : st.args.toString(), onEvent);
                }
                onEvent.accept(StreamEvent.toolEnd(toolName, callId));
            }
            case REQUIRE_USER_CONFIRM -> {
                // 工具执行前需用户确认：只有未被预授权规则覆盖的工具才会走到这里（弹窗）。
                // 已授权（永久/回合）的工具在 streamChat 入口已同步为 ALLOW 规则，
                // 框架直接静默执行，不触发本事件、也不注入任何恢复消息（LLM 无感）。
                // 拒绝时由框架回 "Permission denied by user" 工具结果给 LLM（有感）。
                if (event instanceof RequireUserConfirmEvent e) {
                    List<ToolUseBlock> tools = e.getToolCalls();
                    // 登记待确认工具 + 确认截止时间：用户永不响应时由 sweepExpiredConfirmations
                    // 兜底取消，否则 SSE 连接与会话 Map 条目会永久常驻（稳定的 OOM 路径）
                    sessions.armPendingConfirm(sessionId, tools,
                            agentScopeProperties.getAgent().getConfirmTimeoutMinutes());
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
     * 编排审计上报（P1）
     * <p>
     * team 场景的控制流由主 LLM 依提示词自行执行，系统侧无强制力。本方法在回合结束时
     * 比对「场景计划的阶段」与主智能体自报的 {@code <orchestration-audit>} 标记，把
     * 「编排是否真的按计划发生」由不可观测变为可观测，并在不一致时推送 context 事件给 UI。
     * <p>
     * 注意：这是可观测性手段，不能阻止 LLM 漏做或谎报；执行保证需依赖确定性编排执行器。
     * 审计失败绝不影响主流程。
     */
    private void emitOrchestrationAudit(String sessionId, ToolTrace trace,
                                        Consumer<StreamEvent> onEvent) {
        try {
            String workspaceId = sessions.workspaceOf(sessionId);
            if (workspaceId == null) {
                return;
            }
            String workflowJson = scenarioResolver.activeWorkflowJson(workspaceId);
            if (workflowJson == null || workflowJson.isBlank()) {
                return;
            }
            OrchestrationAuditVerifier.verify(workflowJson, trace.reply.toString())
                    .ifPresent(result -> {
                        if (result.consistent()) {
                            log.debug("编排审计通过: session={}, {}", sessionId, result.summary());
                            return;
                        }
                        log.warn("编排审计不一致: session={}, planned={}, executed={}, detail={}",
                                sessionId, result.plannedStages(), result.executedStages(),
                                result.summary());
                        onEvent.accept(StreamEvent.context(buildAuditJson(result)));
                    });
        } catch (Exception e) {
            log.debug("编排审计跳过（不影响主流程）: session={}, {}", sessionId, e.getMessage());
        }
    }

    /** 构造编排审计告警的 context 事件 JSON */
    private String buildAuditJson(OrchestrationAuditVerifier.AuditResult result) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "orchestration_audit");
            root.put("auditPresent", result.auditPresent());
            root.put("plannedStages", result.plannedStages());
            root.put("executedStages", result.executedStages());
            root.put("skippedStages", result.skippedStages());
            root.put("mismatches", result.mismatches());
            root.put("message", "编排执行与场景计划不一致：" + result.summary());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"type\":\"orchestration_audit\",\"message\":\"编排执行与场景计划不一致\"}";
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
