package com.xinl.easyclaw.agent.team;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamWakeups;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * team 运行时 —— 成员生命周期、wakeup 路由与成员回合执行。
 *
 * <p><b>对齐 service 的模型</b>：成员 = 独立 Agent 实例 + 独立会话（sessionId =
 * {@code <teamSessionId>/members/<name>}），由 wakeup 驱动回合（对齐 service 的
 * SessionTurnRunner.runTurnAsync）。与 easy-claw 既有 agent_spawn 派发的本质区别：
 * spawn 派发的成员是「一次性任务执行者」（跑完即弃），team 成员是「常驻协作角色」
 * （有任务板、有邮箱、可被 lead 与同伴反复唤醒）。
 *
 * <p><b>wakeup 路由</b>：{@link TeamWakeups} 是进程级单 hook，本服务在启动时注册，
 * 按 (teamName, memberName) 路由到对应成员。成员正忙时返回 false——消息已落邮箱
 * （LocalTeamClient.sendMessage 先写 store 再 wake），成员当前回合结束后的
 * listMessages 能看到，不丢。
 *
 * <p><b>成员构建委托</b>：{@link MemberAgentFactory} 回调由上层（AgentService/
 * WorkspaceAgentBuilder）提供——成员 Agent 的完整装配（模型/工具面/文件系统）是
 * workspace 装配逻辑的事，本服务只管生命周期与调度。工厂为 null 时（纯协议运行，
 * 如单测）spawn 只登记成员表不建实例。
 */
@Service
public class TeamRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(TeamRuntimeService.class);

    /** lead 可用动作（对齐 TeamTool javadoc 的角色分组：create/assign/spawn/shutdown/approve/complete）。 */
    public static final List<String> LEAD_ACTIONS = List.of(
            "listTasks", "createTask", "assignTask", "claimTask", "unclaimTask",
            "completeTask", "failTask", "listClaimableTasks",
            "sendMessage", "broadcastMessage", "listMessages", "listMembers",
            "spawnMember", "shutdownMember", "approvePlan", "rejectPlan", "completeTeam");

    /** 成员可用动作（list/claim/message + 计划提交）。 */
    public static final List<String> MEMBER_ACTIONS = List.of(
            "listTasks", "listClaimableTasks", "claimTask", "unclaimTask",
            "completeTask", "failTask",
            "sendMessage", "broadcastMessage", "listMessages", "listMembers",
            "submitPlan");

    /** 成员 Agent 构建回调。client 传入以便成员 Agent 挂同一个 team 的 teamsMode。 */
    public interface MemberAgentFactory {
        HarnessAgent create(SubagentDeclaration declaration, String memberSessionId,
                            TeamContext context, EasyClawTeamClient client);
    }

    private final SubagentLoader subagentLoader;
    private final ScenarioResolver scenarioResolver;
    private final MemberAgentFactory memberAgentFactory;
    private final com.xinl.easyclaw.workspace.WorkspaceAgentBuilder workspaceAgentBuilder;
    private final com.xinl.easyclaw.workspace.WorkspaceManager workspaceManager;

    /** teamKey = workspaceId + "/" + sessionId → 运行时。 */
    private final ConcurrentHashMap<String, TeamRuntime> runtimes = new ConcurrentHashMap<>();

    /** lead team 变体实例缓存（key 同 runtimes）：team 会话用独立 Agent，避免 teamsMode 污染共享单例。 */
    private final ConcurrentHashMap<String, HarnessAgent> leadAgents = new ConcurrentHashMap<>();

    static final class MemberHandle {
        final String name;
        final String memberSessionId;
        final TeamContext context;
        final HarnessAgent agent;
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile Disposable currentTurn;

        MemberHandle(String name, String memberSessionId, TeamContext context, HarnessAgent agent) {
            this.name = name;
            this.memberSessionId = memberSessionId;
            this.context = context;
            this.agent = agent;
        }
    }

    static final class TeamRuntime {
        final String workspaceId;
        final String sessionId;
        final String teamName;
        final String objective;
        final EasyClawTeamClient client;
        final Map<String, MemberHandle> members = new ConcurrentHashMap<>();

        TeamRuntime(String workspaceId, String sessionId, String teamName, String objective,
                    EasyClawTeamClient client) {
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.teamName = teamName;
            this.objective = objective;
            this.client = client;
        }
    }

    public TeamRuntimeService(SubagentLoader subagentLoader,
                              ScenarioResolver scenarioResolver,
                              MemberAgentFactory memberAgentFactory,
                              com.xinl.easyclaw.workspace.WorkspaceAgentBuilder workspaceAgentBuilder,
                              com.xinl.easyclaw.workspace.WorkspaceManager workspaceManager) {
        this.subagentLoader = subagentLoader;
        this.scenarioResolver = scenarioResolver;
        this.memberAgentFactory = memberAgentFactory;
        this.workspaceAgentBuilder = workspaceAgentBuilder;
        this.workspaceManager = workspaceManager;
    }

    /** 进程级 wakeup hook：TeamWakeups 是静态单槽，启动时注册一次。 */
    @PostConstruct
    void registerWakeupHook() {
        TeamWakeups.register((teamName, memberName, notice) -> wakeMember(teamName, memberName, notice));
        log.info("[team] wakeup hook 已注册");
    }

    @PreDestroy
    void shutdownAll() {
        for (TeamRuntime rt : runtimes.values()) {
            stopTeam(rt.workspaceId, rt.sessionId);
        }
    }

    // ── team 生命周期 ────────────────────────────────────────────────────

    /**
     * 为 lead 会话启动 team。roster 为空时只建 team（成员由 lead 运行时 spawnMember 动态拉起），
     * 非空时按名册预建（对齐 TeamCreateSpec.members）。
     *
     * <p>store 按 workspace 隔离：namespace 第一段用 workspaceId（单 FileBaseStore 实例
     * 服务多 workspace，任务板/邮箱/成员表互不可见）。
     *
     * @return team 运行时句柄（内部用，调用方一般不持有）
     */
    public TeamRuntime startTeam(String workspaceId, String sessionId, String teamName,
                                 String objective, List<String> rosterNames) {
        String key = runtimeKey(workspaceId, sessionId);
        TeamRuntime existing = runtimes.get(key);
        if (existing != null) {
            log.warn("[team] 会话已有运行中 team（{}），先停旧再建新", existing.teamName);
            stopTeam(workspaceId, sessionId);
        }
        EasyClawTeamClient client = new EasyClawTeamClient(
                teamStore(workspaceId),
                new EasyClawTeamClient.MemberLifecycle() {
                    @Override
                    public void spawn(String ns, String tn, String name, String agentRef, String prompt) {
                        doSpawnMember(workspaceId, sessionId, tn, name, agentRef, prompt);
                    }

                    @Override
                    public void shutdown(String ns, String tn, String memberName) {
                        doShutdownMember(workspaceId, sessionId, tn, memberName);
                    }
                });
        TeamRuntime rt = new TeamRuntime(workspaceId, sessionId, teamName, objective, client);
        runtimes.put(key, rt);
        List<TeamMemberSpec> specs = new ArrayList<>();
        if (rosterNames != null) {
            for (String n : rosterNames) {
                specs.add(new TeamMemberSpec(n, n, "", "local"));
            }
        }
        client.createTeam(new TeamCreateSpec(teamName, workspaceId, objective, "main", "", specs))
                .doOnSuccess(v -> log.info("[team] 已创建: team={}, objective={}", teamName, objective))
                .doOnError(e -> log.warn("[team] 创建失败: team={}, err={}", teamName, e.toString()))
                .subscribe();
        return rt;
    }

    /** lead 会话结束时收尾：completeTeam + 全成员下线。 */
    public void stopTeam(String workspaceId, String sessionId) {
        TeamRuntime rt = runtimes.remove(runtimeKey(workspaceId, sessionId));
        if (rt == null) {
            return;
        }
        for (MemberHandle h : rt.members.values()) {
            doShutdownMember(workspaceId, sessionId, rt.teamName, h.name);
        }
        rt.client.completeTeam(workspaceId, rt.teamName)
                .doOnError(e -> log.warn("[team] completeTeam 失败: {}, err={}",
                        rt.teamName, e.toString()))
                .subscribe();
        log.info("[team] 已收尾: team={}, session={}", rt.teamName, sessionId);
    }

    // ── lead team 变体装配 ───────────────────────────────────────────────

    /**
     * 取（或构建）lead 的 team 变体 Agent。
     *
     * <p><b>为什么不用 workspace 共享单例</b>：teamsMode 在 build 时挂 TeamsMiddleware
     * 并注册 team 工具，绑定 (teamName, sessionId)——共享单例服务多会话，挂上后
     * 所有会话都被污染。对齐 service 的做法：team 会话用独立实例
     * （service 的 cacheKey 追加 {@code /team-<sessionId>}），缓存随会话释放。
     *
     * <p>装配走 {@link com.xinl.easyclaw.workspace.WorkspaceAgentBuilder} 主重载
     * （完整场景绑定/名册/压缩/记忆），仅追加 teamsMode——lead 保留既有工具面
     * （过渡期 agent_spawn 与 team 工具双通道并存，批 4 裁剪）。
     *
     * @param teamName    team 名（场景绑定或默认 main）
     * @param objective   团队目标（场景描述或用户消息首句）
     * @param rosterNames 预建成员名册（可空——成员由 lead 运行时 spawnMember 拉起）
     */
    public synchronized HarnessAgent leadAgentFor(String workspaceId, String sessionId,
                                                  String teamName, String objective,
                                                  List<String> rosterNames) {
        String key = runtimeKey(workspaceId, sessionId);
        HarnessAgent cached = leadAgents.get(key);
        if (cached != null) {
            return cached;
        }
        TeamRuntime rt = startTeam(workspaceId, sessionId, teamName, objective, rosterNames);
        com.xinl.easyclaw.workspace.WorkspaceContext ws = workspaceManager.getWorkspace(workspaceId);
        if (ws == null) {
            throw new IllegalStateException("工作区不存在: " + workspaceId);
        }
        java.nio.file.Path wsPath = ws.getPath();
        java.nio.file.Path easyClawDir = wsPath.resolve(".easyClaw");
        TeamContext leadCtx = new TeamContext(
                teamName, workspaceId, objective, "lead", true, List.of(), LEAD_ACTIONS);
        HarnessAgent lead = workspaceAgentBuilder.build(
                workspaceId, subagentLoader.MAIN_AGENT_ID, ws.getName(), wsPath, easyClawDir,
                null, "solo", rt.client, leadCtx, sessionId);
        leadAgents.put(key, lead);
        log.info("[team] lead 变体已构建: team={}, session={}", teamName, sessionId);
        return lead;
    }

    /** lead 会话释放：停 team + 丢弃 lead 变体实例（AgentService.releaseSession 调用）。 */
    public void releaseSession(String workspaceId, String sessionId) {
        leadAgents.remove(runtimeKey(workspaceId, sessionId));
        stopTeam(workspaceId, sessionId);
    }

    // ── 成员生命周期 ─────────────────────────────────────────────────────

    /**
     * spawn 成员：声明查找 → 成员 Agent 构建 → handle 登记。
     * 由 EasyClawTeamClient.spawnMember 的 lifecycle 回调触发（lead 调 team 工具时）。
     */
    public void doSpawnMember(String workspaceId, String sessionId, String teamName,
                              String memberName, String agentRef, String prompt) {
        TeamRuntime rt = findRuntime(workspaceId, sessionId, teamName);
        if (rt == null) {
            log.warn("[team] spawn 被拒：无运行中 team={}, session={}", teamName, sessionId);
            return;
        }
        if (rt.members.containsKey(memberName)) {
            log.info("[team] 成员已存在，跳过重复 spawn: {}", memberName);
            return;
        }
        SubagentDeclaration decl = findDeclaration(workspaceId, agentRef != null ? agentRef : memberName);
        String memberSessionId = sessionId + "/members/" + memberName;
        TeamContext ctx = new TeamContext(
                teamName, workspaceId,
                rt.objective, memberName, false,
                List.of(), MEMBER_ACTIONS);
        HarnessAgent agent = null;
        if (memberAgentFactory != null && decl != null) {
            try {
                agent = memberAgentFactory.create(decl, memberSessionId, ctx, rt.client);
            } catch (Exception e) {
                log.warn("[team] 成员 Agent 构建失败: member={}, err={}", memberName, e.toString());
            }
        } else if (memberAgentFactory != null) {
            log.warn("[team] 未找到成员声明 [{}]，spawn 仅登记不建实例", agentRef);
        }
        rt.members.put(memberName, new MemberHandle(memberName, memberSessionId, ctx, agent));
        log.info("[team] 成员已登记: team={}, member={}, decl={}, agent={}",
                teamName, memberName, decl != null, agent != null);
    }

    /** shutdown 成员：中断当前回合 + 移除 handle。 */
    public void doShutdownMember(String workspaceId, String sessionId, String teamName,
                                 String memberName) {
        TeamRuntime rt = findRuntime(workspaceId, sessionId, teamName);
        if (rt == null) {
            return;
        }
        MemberHandle h = rt.members.remove(memberName);
        if (h == null) {
            return;
        }
        Disposable turn = h.currentTurn;
        if (turn != null && !turn.isDisposed()) {
            turn.dispose();
        }
        log.info("[team] 成员已移除: team={}, member={}", teamName, memberName);
    }

    // ── wakeup 与成员回合 ────────────────────────────────────────────────

    /**
     * 唤醒成员跑回合。返回 false = 无此成员或成员正忙（消息已在邮箱，不丢）。
     * 由 TeamWakeups hook（邮箱消息）与任务分派路径调用。
     */
    public boolean wakeMember(String teamName, String memberName, String notice) {
        for (TeamRuntime rt : runtimes.values()) {
            if (!rt.teamName.equals(teamName)) {
                continue;
            }
            MemberHandle h = rt.members.get(memberName);
            if (h == null || h.agent == null) {
                return false;
            }
            if (!h.running.compareAndSet(false, true)) {
                log.info("[team] 成员正忙，消息留邮箱: team={}, member={}", teamName, memberName);
                return false;
            }
            try {
                runMemberTurn(rt, h, notice);
                return true;
            } catch (Exception e) {
                h.running.set(false);
                log.warn("[team] 成员回合启动失败: member={}, err={}", memberName, e.toString());
                return false;
            }
        }
        return false;
    }

    /**
     * 成员回合执行：boundedElastic 上跑 streamEvents，事件落日志（批 4 接 WS 推送）。
     * 回合结束清 running 标记；邮箱里有积压消息时由 lead 下次唤醒继续。
     */
    private void runMemberTurn(TeamRuntime rt, MemberHandle h, String notice) {
        RuntimeContext rc = RuntimeContext.builder()
                .sessionId(h.memberSessionId)
                .build();
        String msg = notice == null || notice.isBlank()
                ? "[team] 你被唤醒，请用 listMessages 查看邮箱、listTasks 查看任务板，继续协作。"
                : notice;
        MemberEventSink sink = memberEventSink;
        // 工具结果文本缓冲：结果走 ToolResultTextDeltaEvent 流式给，End 事件只带状态——
        // 对齐 AgentService 主回合的做法（累积后一次性下发），但用回合级局部 Map，
        // 不依赖 SessionRegistry（成员回合生命周期独立于主回合）。
        Map<String, StringBuilder> toolResultBuf = new java.util.HashMap<>();
        h.currentTurn = h.agent.streamEvents(msg, rc)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    log.debug("[team-member:{}] event {}", h.name, event);
                    if (sink == null) {
                        return;
                    }
                    StreamEvent se = translateMemberEvent(
                            h.name, h.memberSessionId, event, toolResultBuf);
                    if (se != null) {
                        sink.accept(rt.sessionId, se);
                    }
                })
                .doFinally(sig -> {
                    h.running.set(false);
                    log.info("[team] 成员回合结束: team={}, member={}, signal={}",
                            rt.teamName, h.name, sig);
                })
                .subscribe(
                        ev -> { /* 翻译推送见 doOnNext */ },
                        err -> log.warn("[team] 成员回合异常: member={}, err={}",
                                h.name, err.toString()));
    }

    /**
     * 成员 AgentEvent → StreamEvent.subagent* 翻译。subId 用成员会话 id（稳定实例身份，
     * 前端按 id 分卡）。工具结果文本经 toolResultBuf 累积、End 时一次性下发。
     * 返回 null 表示该事件无需外发。
     */
    private StreamEvent translateMemberEvent(String memberName, String memberSessionId,
                                             AgentEvent event,
                                             Map<String, StringBuilder> toolResultBuf) {
        if (event instanceof TextBlockDeltaEvent e) {
            return StreamEvent.subagentText(memberName, e.getDelta(), memberSessionId);
        }
        if (event instanceof ThinkingBlockDeltaEvent e) {
            return StreamEvent.subagentReasoning(memberName, e.getDelta(), memberSessionId);
        }
        if (event instanceof ToolCallStartEvent e) {
            toolResultBuf.remove(e.getToolCallId());
            return StreamEvent.subagentTool(memberName, e.getToolCallName(), memberSessionId);
        }
        if (event instanceof ToolCallDeltaEvent e) {
            return StreamEvent.subagentToolArgs(memberName, e.getDelta(), memberSessionId);
        }
        if (event instanceof ToolResultTextDeltaEvent e) {
            toolResultBuf.computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                    .append(e.getDelta());
            return null;
        }
        if (event instanceof ToolResultEndEvent e) {
            StringBuilder buf = toolResultBuf.remove(e.getToolCallId());
            String result = buf != null ? buf.toString() : "";
            // 超长结果截断（对齐主回合口径：前端渲染不需要全量原始输出）
            if (result.length() > 20_000) {
                result = result.substring(0, 20_000) + "\n…（已截断）";
            }
            return StreamEvent.subagentToolResult(memberName,
                    e.getState() != null ? e.getState().name() : "ok",
                    result, memberSessionId);
        }
        if (event.getType() == AgentEventType.AGENT_END) {
            return StreamEvent.subagentEnd(memberName, memberSessionId);
        }
        return null;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** lead 会话是否已有运行中 team。 */
    public boolean hasTeam(String workspaceId, String sessionId) {
        return runtimes.containsKey(runtimeKey(workspaceId, sessionId));
    }

    // ── 成员事件推送（批 4）────────────────────────────────────────────

    /**
     * 成员回合事件出口：由 WS 层注册（{@code ChatWebSocketHandler::sendJson}），
     * 成员回合的事件实时推给前端（复用 subagent* 事件族渲染——前端折叠块+steps
     * 时间线现成，成员名即 agentName，subId=成员会话 id）。
     * 未注册时（单测/无 WS）事件只落日志，行为与批 3 一致。
     */
    public interface MemberEventSink {
        void accept(String sessionId, StreamEvent event);
    }

    private volatile MemberEventSink memberEventSink;

    public void setMemberEventSink(MemberEventSink sink) {
        this.memberEventSink = sink;
    }

    /** 取 lead 会话的 team 客户端（供 lead Agent 装配 teamsMode 用，批 3）。 */
    public EasyClawTeamClient clientOf(String workspaceId, String sessionId) {
        TeamRuntime rt = runtimes.get(runtimeKey(workspaceId, sessionId));
        return rt == null ? null : rt.client;
    }

    private TeamRuntime findRuntime(String workspaceId, String sessionId, String teamName) {
        TeamRuntime rt = runtimes.get(runtimeKey(workspaceId, sessionId));
        return rt != null && rt.teamName.equals(teamName) ? rt : null;
    }

    private SubagentDeclaration findDeclaration(String workspaceId, String name) {
        try {
            var binding = scenarioResolver.activeBinding(workspaceId);
            for (SubagentDeclaration d : subagentLoader.loadMerged(binding)) {
                if (d.getName().equals(name)) {
                    return d;
                }
            }
        } catch (Exception e) {
            log.warn("[team] 成员声明查找失败: name={}, err={}", name, e.toString());
        }
        return null;
    }

    private String objectiveOf(TeamRuntime rt) {
        return rt.objective;
    }

    /** team store：按 workspace 缓存（同 workspace 的任务板/邮箱/成员表共享一个实例，CAS 才有效）。 */
    private final ConcurrentHashMap<String, io.agentscope.harness.agent.filesystem.remote.store.BaseStore>
            teamStores = new ConcurrentHashMap<>();

    private io.agentscope.harness.agent.filesystem.remote.store.BaseStore teamStore(String workspaceId) {
        return teamStores.computeIfAbsent(workspaceId, k -> new FileBaseStore((String) null));
    }

    private static String runtimeKey(String workspaceId, String sessionId) {
        return workspaceId + "/" + sessionId;
    }
}
