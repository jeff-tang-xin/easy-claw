package com.xinl.easyclaw.agent.team;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamWakeups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * easy-claw 本地 {@link TeamClient} —— 组合包装 harness 的 {@link LocalTeamClient}，
 * 补齐它因依赖 control-plane 而抛 {@link UnsupportedOperationException} 的五个方法：
 * 成员生命周期（spawn/shutdown）与计划审批（submit/approve/reject）。
 *
 * <p><b>为什么组合而不是继承</b>：{@code LocalTeamClient} 是 final 的，且其任务板 CAS、
 * 邮箱唤醒（{@link TeamWakeups}）逻辑已经完整可用——任务流转语义（PENDING→IN_PROGRESS→
 * COMPLETED/FAILED、blockedBy 依赖、领任务幂等）不值得复刻，直接委托。
 *
 * <p><b>成员生命周期落点</b>：spawn/shutdown 的实际执行委托给 {@link MemberLifecycle}
 * 回调（由 {@link TeamRuntimeService} 提供）——本类只负责「任务板/成员表的簿记」与
 * 「回调的触发时机」，不持有 Agent 实例。这样成员如何构建（轻量/完整装配）是
 * 上层的事，本类保持纯协议层。
 *
 * <p><b>计划审批语义</b>：对齐 service 的 plan 审批流——成员 submitPlan 把计划文本
 * 落 store 并唤醒 lead 审批；lead approve/reject 后回写状态并唤醒提交成员。
 * 与 easy-claw 既有 HITL（RequireUserConfirmEvent 弹窗）的关系：计划审批是
 * 「成员→lead」的团队内审批，不是「工具→用户」的安全确认，两者正交。
 */
public final class EasyClawTeamClient implements TeamClient {

    private static final Logger log = LoggerFactory.getLogger(EasyClawTeamClient.class);

    private final LocalTeamClient delegate;
    private final BaseStore store;
    private final MemberLifecycle lifecycle;
    private final AtomicLong planSeq = new AtomicLong();

    /** 成员生命周期回调（TeamRuntimeService 实现）。 */
    public interface MemberLifecycle {
        void spawn(String namespace, String teamName, String name, String agentRef, String prompt);

        void shutdown(String namespace, String teamName, String memberName);
    }

    public EasyClawTeamClient(BaseStore store, MemberLifecycle lifecycle) {
        this.store = store;
        this.delegate = new LocalTeamClient(store);
        this.lifecycle = lifecycle;
    }

    /** 暴露内部 delegate 供 TeamRuntimeService 复用任务板方法（createTeam 等）。 */
    public LocalTeamClient delegate() {
        return delegate;
    }

    // ── 成员生命周期（补 LocalTeamClient 的 Unsupported）──────────────────

    @Override
    public Mono<Void> spawnMember(
            String namespace, String teamName, String name, String agentRef, String prompt) {
        return Mono.<Void>fromRunnable(() -> {
                    lifecycle.spawn(namespace, teamName, name, agentRef, prompt);
                    // 成员表登记：status=Working，与 LocalTeamClient.createTeam 的成员行同构
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("agentRef", agentRef == null ? "" : agentRef);
                    row.put("status", "Working");
                    row.put("note", prompt == null ? "" : prompt);
                    row.put("deployMode", "local");
                    row.put("lead", false);
                    store.put(memberNs(namespace, teamName), name, row);
                    log.info("[team] 成员已生成: team={}, member={}, agentRef={}",
                            teamName, name, agentRef);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> shutdownMember(String namespace, String teamName, String memberName) {
        return Mono.<Void>fromRunnable(() -> {
                    lifecycle.shutdown(namespace, teamName, memberName);
                    var item = store.get(memberNs(namespace, teamName), memberName);
                    if (item != null && item.value() != null) {
                        Map<String, Object> row = new LinkedHashMap<>(item.value());
                        row.put("status", "Offline");
                        store.put(memberNs(namespace, teamName), memberName, row);
                    }
                    log.info("[team] 成员已下线: team={}, member={}", teamName, memberName);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ── 计划审批（补 LocalTeamClient 的 Unsupported）─────────────────────

    @Override
    public Mono<Void> submitPlan(
            String namespace, String teamName, String memberName, String planText) {
        return Mono.<Void>fromRunnable(() -> {
                    long id = planSeq.incrementAndGet();
                    String planId = "plan-" + id;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("planId", planId);
                    row.put("team", teamName);
                    row.put("member", memberName);
                    row.put("planText", planText == null ? "" : planText);
                    row.put("state", "PENDING");
                    store.put(planNs(namespace, teamName), planId, row);
                    // 唤醒 lead 审批（同步落盘——对齐 LocalTeamClient.deliverNotification：
                    // fire-and-forget 会与调用方随后读邮箱竞态）
                    delegate.sendMessage(
                            namespace, teamName, memberName, "lead",
                            "[team] 成员 " + memberName + " 提交计划 " + planId
                                    + " 待审批。\n\n" + (planText == null ? "" : planText))
                            .block();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> approvePlan(String namespace, String teamName, String memberName) {
        return settlePlan(namespace, teamName, memberName, "APPROVED",
                "你的计划已获批准，请按计划执行。");
    }

    @Override
    public Mono<Void> rejectPlan(String namespace, String teamName, String memberName) {
        return settlePlan(namespace, teamName, memberName, "REJECTED",
                "你的计划被驳回，请根据 lead 的反馈修订后重新提交。");
    }

    private Mono<Void> settlePlan(String namespace, String teamName, String memberName,
                                  String state, String notice) {
        return Mono.<Void>fromRunnable(() -> {
                    var items = store.search(planNs(namespace, teamName), 100, 0);
                    // 找该成员最新的 PENDING 计划
                    String latestPlanId = null;
                    long latestSeq = -1;
                    for (var item : items) {
                        if (item.value() == null) {
                            continue;
                        }
                        Object member = item.value().get("member");
                        Object st = item.value().get("state");
                        if (memberName.equals(member) && "PENDING".equals(st)) {
                            long seq = item.version();
                            if (seq > latestSeq) {
                                latestSeq = seq;
                                latestPlanId = (String) item.value().get("planId");
                            }
                        }
                    }
                    if (latestPlanId != null) {
                        var cur = store.get(planNs(namespace, teamName), latestPlanId);
                        if (cur != null && cur.value() != null) {
                            Map<String, Object> row = new LinkedHashMap<>(cur.value());
                            row.put("state", state);
                            store.put(planNs(namespace, teamName), latestPlanId, row);
                        }
                    }
                    delegate.sendMessage(namespace, teamName, "lead", memberName,
                            "[team] 计划审批结果: " + state + "\n\n" + notice)
                            .block();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ── 任务板/邮箱/成员表：全部委托 LocalTeamClient ─────────────────────

    @Override
    public Mono<List<io.agentscope.harness.agent.team.TeamTask>> listTasks(
            String namespace, String teamName) {
        return delegate.listTasks(namespace, teamName);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> createTask(
            String namespace, String teamName, String subject, String description,
            List<String> blockedBy, String owner) {
        return delegate.createTask(namespace, teamName, subject, description, blockedBy, owner);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion) {
        return delegate.assignTask(namespace, teamName, taskId, owner, expectedVersion);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> claimTask(
            String namespace, String teamName, String taskId, String claimedBy,
            long expectedVersion) {
        return delegate.claimTask(namespace, teamName, taskId, claimedBy, expectedVersion);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion) {
        return delegate.unclaimTask(namespace, teamName, taskId, expectedVersion);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> completeTask(
            String namespace, String teamName, String taskId, String result) {
        return delegate.completeTask(namespace, teamName, taskId, result);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamTask> failTask(
            String namespace, String teamName, String taskId, String reason) {
        return delegate.failTask(namespace, teamName, taskId, reason);
    }

    @Override
    public Mono<List<io.agentscope.harness.agent.team.TeamTask>> listClaimableTasks(
            String namespace, String teamName, String forMember) {
        return delegate.listClaimableTasks(namespace, teamName, forMember);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content) {
        return delegate.sendMessage(namespace, teamName, from, to, content);
    }

    @Override
    public Mono<List<io.agentscope.harness.agent.team.TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content) {
        return delegate.broadcastMessage(namespace, teamName, from, content);
    }

    @Override
    public Mono<List<io.agentscope.harness.agent.team.TeamMessage>> listMessages(
            String namespace, String teamName, int limit) {
        return delegate.listMessages(namespace, teamName, limit);
    }

    @Override
    public Mono<List<io.agentscope.harness.agent.team.TeamMemberInfo>> listMembers(
            String namespace, String teamName) {
        return delegate.listMembers(namespace, teamName);
    }

    @Override
    public Mono<io.agentscope.harness.agent.team.TeamInfo> createTeam(
            io.agentscope.harness.agent.team.TeamCreateSpec spec) {
        return delegate.createTeam(spec);
    }

    @Override
    public Mono<Void> completeTeam(String namespace, String teamName) {
        return delegate.completeTeam(namespace, teamName);
    }

    private static List<String> memberNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName, "members");
    }

    private static List<String> planNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName, "plans");
    }
}
