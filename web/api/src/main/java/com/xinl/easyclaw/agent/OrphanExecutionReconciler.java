package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 孤儿回合回收器 —— 对齐 agentscope-service {@code CoordinationReconciler} 的
 * {@code session.orphaned_after_lease_expiry} 语义。
 * <p>
 * <b>治理对象</b>：持有回合租约但事件流已长时间静默的「孤儿回合」。产生路径：
 * <ul>
 *   <li>工具卡死在不可中断的阻塞调用里（{@code Process.waitFor}、管道 read、
 *       GraalPy 原生执行），中断标志永远读不到；</li>
 *   <li>Flux 订阅泄漏：前端断连且无人调用 stopChat，流永不终止；</li>
 *   <li>框架层异常导致回合收尾回调（whenComplete）丢失，租约未释放。</li>
 * </ul>
 * 此前这些路径的唯一出口是用户手动点「停止」（stopChat）——前端断连时无人能点，
 * 会话永久卡在 running，callGates 被占死，后续消息无声排队。
 * <p>
 * <b>回收动作</b>（与用户手动停止同路径，记忆落盘有保障）：
 * {@code stopChat}（宽限中断 + 兜底 dispose + recoverStuckAgent 重建 Agent）→
 * {@code releaseSession}（清理会话内存状态）→ 释放租约。
 * <p>
 * <b>误杀防护</b>：TTL 默认 30 分钟（与 ToolExecutor 工具超时上限一致），且心跳由
 * 每个流式事件刷新 —— 只要回合还在产出（文本/工具调用/工具结果），就不会被回收。
 * 可通过 {@code agentscope.agent.orphan-turn-ttl-minutes} 调整。
 */
@Component
public class OrphanExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrphanExecutionReconciler.class);

    private final AgentService agentService;
    private final TurnLeaseService turnLeases;
    private final SessionRegistry sessions;

    /** 孤儿判定阈值（分钟）：事件静默超过该时长的回合视为孤儿 */
    @Value("${agentscope.agent.orphan-turn-ttl-minutes:30}")
    private long orphanTurnTtlMinutes;

    public OrphanExecutionReconciler(AgentService agentService,
                                     TurnLeaseService turnLeases,
                                     SessionRegistry sessions) {
        this.agentService = agentService;
        this.turnLeases = turnLeases;
        this.sessions = sessions;
    }

    /**
     * 周期回收孤儿回合。60s 一轮（单实例无需 service 跨副本 Reconciler 的 15s 密度）。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void reconcileOrphanTurns() {
        long ttlMs = orphanTurnTtlMinutes * 60_000L;
        long cutoff = System.currentTimeMillis() - ttlMs;
        List<TurnLeaseService.TurnLease> orphans = turnLeases.expiredBefore(cutoff);
        if (orphans.isEmpty()) {
            return;
        }
        for (TurnLeaseService.TurnLease lease : orphans) {
            reclaimOrphan(lease);
        }
    }

    private void reclaimOrphan(TurnLeaseService.TurnLease lease) {
        String sessionId = lease.sessionId();
        String workspaceId = lease.workspaceId();
        long silentMs = System.currentTimeMillis() - lease.lastActivityMs().get();
        // 先摘租约再收尾：收尾过程中的事件（若有）不应再刷新心跳导致重复进入本方法
        turnLeases.release(sessionId);
        log.warn("[孤儿回收] 回合事件静默 {}ms 超过 TTL（{}min），强制收尾: sessionId={}, workspaceId={}",
                silentMs, orphanTurnTtlMinutes, sessionId, workspaceId);
        try {
            // 尽力通知前端（断连场景 emitter 为 null，属正常）
            java.util.function.Consumer<StreamEvent> emitter = sessions.takePendingEmitter(sessionId);
            if (emitter != null) {
                emitter.accept(StreamEvent.error(
                        "回合长时间无响应（事件静默超过 " + orphanTurnTtlMinutes + " 分钟），已被系统自动回收。"));
            }
        } catch (Exception ex) {
            log.debug("[孤儿回收] 通知前端失败（不影响回收）: sessionId={}, err={}",
                    sessionId, ex.toString());
        }
        try {
            agentService.stopChat(workspaceId, sessionId);
        } catch (Exception ex) {
            log.warn("[孤儿回收] stopChat 异常: sessionId={}, err={}", sessionId, ex.toString());
        }
        try {
            agentService.releaseSession(sessionId);
        } catch (Exception ex) {
            log.warn("[孤儿回收] releaseSession 异常: sessionId={}, err={}", sessionId, ex.toString());
        }
        log.info("[孤儿回收] 完成: sessionId={}", sessionId);
    }
}
