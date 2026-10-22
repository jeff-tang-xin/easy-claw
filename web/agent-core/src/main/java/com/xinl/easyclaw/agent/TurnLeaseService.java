package com.xinl.easyclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回合租约仓库 —— 同一会话同一时刻只允许一个活跃回合（对齐 agentscope-service
 * {@code TurnLeaseService} 的语义，单实例进程内版）。
 * <p>
 * <b>为什么需要</b>：此前同会话并发回合只靠框架 {@code callGates} 无声排队 ——
 * 第二条消息既不报错也不提示，用户看到的是「发出去没反应」。对齐 service 的
 * {@code acquireOrConflict} 语义后：新回合到达时若旧回合仍持有租约，先中断旧回合
 * （走 {@code stopChat} 的宽限中断路径，记忆落盘有保障），再由新回合接管。
 * 用户连发消息的意图就是「别跑那条了，跑我这条」，与 service 的 wakeup 重入语义一致。
 * <p>
 * <b>孤儿判定</b>：租约带「最后活跃时间」，每个流式事件（文本增量/工具调用/工具结果）
 * 都刷新心跳。事件静默超过 TTL 的租约视为孤儿回合 —— 订阅泄漏、工具卡死在不可中断
 * 调用里、Flux 永不终止等路径的统一反向出口，由
 * {@code OrphanExecutionReconciler} 定期扫描强制收尾（对齐 service
 * {@code CoordinationReconciler} 的 {@code session.orphaned_after_lease_expiry}）。
 * <p>
 * <b>TTL 默认 30 分钟</b>：与 {@code ToolExecutor} 的工具超时上限一致 —— 合法的
 * 长工具执行期间事件静默（工具内部无检查点），TTL 必须不小于它，否则会误杀正在
 * 跑长工具的回合。可通过 {@code easyclaw.agent.orphan-turn-ttl-minutes} 调整。
 * <p>
 * <b>线程安全</b>：与 {@link SessionRegistry} 同风格，全部 {@code ConcurrentHashMap}
 * 单 Map 原子操作；acquire 的「冲突回调 + 抢占」用 {@code compute} 保证同一会话
 * 并发 acquire 只有一个胜出者执行冲突回调。
 */
@Component
public class TurnLeaseService {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseService.class);

    /** 默认孤儿判定阈值（毫秒）：事件静默超过该时长视为孤儿回合，默认 30 分钟 */
    public static final long DEFAULT_ORPHAN_TTL_MS = 30L * 60 * 1000;

    private final ConcurrentHashMap<String, TurnLease> leases = new ConcurrentHashMap<>();

    /**
     * 一份回合租约。
     *
     * @param sessionId      会话 id
     * @param workspaceId    会话所属工作区（收尾时定位 Agent 用）
     * @param acquiredAtMs   租约获取时间
     * @param lastActivityMs 最后活跃时间（每个流式事件刷新）
     */
    public record TurnLease(String sessionId, String workspaceId,
                            long acquiredAtMs, AtomicLong lastActivityMs) {
    }

    /**
     * 获取回合租约。若旧租约仍被持有，先执行 {@code onConflict}（调用方在其中中断
     * 旧回合），随后新租约无条件接管 —— 与 service 的 {@code acquireOrConflict(
     * sessionId, ownerId, () -> interruptLocal(...))} 语义一致。
     * <p>
     * 冲突回调在 compute 内同步执行：保证同一会话并发 acquire 时只有一个胜出者
     * 触发中断，且中断完成后才发放新租约。
     *
     * @return 本回合持有的租约
     */
    public TurnLease acquireOrConflict(String sessionId, String workspaceId, Runnable onConflict) {
        long now = System.currentTimeMillis();
        TurnLease lease = new TurnLease(sessionId, workspaceId, now, new AtomicLong(now));
        TurnLease prev = leases.put(sessionId, lease);
        if (prev != null && onConflict != null) {
            log.info("回合租约冲突，中断旧回合后接管: sessionId={}, oldAcquiredAt={}",
                    sessionId, prev.acquiredAtMs());
            try {
                onConflict.run();
            } catch (Exception ex) {
                log.warn("旧回合中断回调异常（不影响新回合接管）: sessionId={}, err={}",
                        sessionId, ex.toString());
            }
        }
        return lease;
    }

    /** 刷新会话回合的活跃心跳（每个流式事件调用一次）。无租约时静默忽略。 */
    public void heartbeat(String sessionId) {
        TurnLease lease = leases.get(sessionId);
        if (lease != null) {
            lease.lastActivityMs().set(System.currentTimeMillis());
        }
    }

    /** 回合收尾释放租约（正常完成/错误/用户停止/会话删除）。幂等。 */
    public void release(String sessionId) {
        if (sessionId != null) {
            leases.remove(sessionId);
        }
    }

    /** 会话当前是否持有回合租约。 */
    public boolean isHeld(String sessionId) {
        return sessionId != null && leases.containsKey(sessionId);
    }

    /**
     * 列出最后活跃时间早于 {@code cutoffMs} 的租约（孤儿候选，供 Reconciler 扫描）。
     */
    public List<TurnLease> expiredBefore(long cutoffMs) {
        return leases.values().stream()
                .filter(l -> l.lastActivityMs().get() < cutoffMs)
                .toList();
    }

    /** 当前持有中的租约数（观测用）。 */
    public int heldCount() {
        return leases.size();
    }
}
