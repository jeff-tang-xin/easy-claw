package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.config.RetryScope;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 会话级运行时状态仓库。
 * <p>
 * 从 {@code AgentService} 抽出的第一层职责：原先 12 个散落的 {@code ConcurrentHashMap}
 * 字段（待确认工具、回合授权、订阅句柄、护栏计数、确认超时回调……）由本类统一持有，
 * 使「会话状态的生命周期」成为一个可独立推理、可独立测试的单元。
 * <p>
 * <b>为什么必须收敛</b>：这些 Map 的键都是 sessionId，但清理时机各不相同 ——
 * 回合结束清一部分（{@code turnAllowed}）、用户停止清另一部分、会话驱逐清全部。
 * 分散在 90 余处直接 {@code map.remove(sessionId)} 调用时，任何一处漏清都会造成
 * 内存泄漏或（更严重的）授权残留：复用的 sessionId 继承上一会话的工具授权，
 * 新会话将绕过确认弹窗。收敛后清理语义只有三个入口：
 * {@link #endTurn}、{@link #abortTurn}、{@link #release}。
 * <p>
 * <b>线程安全</b>：全部字段为 {@code ConcurrentHashMap}，各方法只做单 Map 原子操作，
 * 不跨 Map 加锁 —— 调用方（事件流回调）本身是单会话串行的，跨 Map 的短暂不一致
 * （如订阅已清、计数未清）不影响正确性，且随后的清理入口会补齐。
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** 子 Agent 结果缓冲单条上限（字符）：超限截断，防止长输出撑爆内存 */
    private static final int MAX_SUBAGENT_BUFFER_CHARS = 200_000;

    /** 待用户确认的工具调用 */
    private final Map<String, List<ToolUseBlock>> pendingConfirms = new ConcurrentHashMap<>();
    /** 本回合已允许的工具名 */
    private final Map<String, Set<String>> turnAllowed = new ConcurrentHashMap<>();
    /** 会话 → Workspace 映射（权限判断按 workspace 隔离） */
    private final Map<String, String> sessionWorkspaces = new ConcurrentHashMap<>();
    /** 子 Agent 调度计数（sessionId → subagentName → 次数），用于循环调度防护 */
    private final Map<String, Map<String, Integer>> subagentCallCounts = new ConcurrentHashMap<>();
    /** 已交付过结果的子 Agent（sessionId → subagentName 集合），用于区分并行批次与串行重派 */
    private final Map<String, Set<String>> subagentDelivered = new ConcurrentHashMap<>();
    /** 子 Agent 工具结果文本缓冲（"sessionId::source" → 累积文本） */
    private final Map<String, StringBuilder> subagentResultBuffers = new ConcurrentHashMap<>();
    /** 每个会话正在运行的流订阅（用于停止时 dispose 取消） */
    private final Map<String, Disposable> sessionDisposables = new ConcurrentHashMap<>();
    /** 是否处于「确认后恢复」阶段：原暂停流结束时抑制其 end，由恢复流统一发送 */
    private final Map<String, AtomicBoolean> resuming = new ConcurrentHashMap<>();
    /** 工具连续失败计数（sessionId → toolName → 次数），用于护栏 */
    private final Map<String, Map<String, Integer>> toolFailCounts = new ConcurrentHashMap<>();
    /** 会话最后活跃时间（sessionId → 毫秒时间戳），用于空闲会话兜底清扫 */
    private final Map<String, Long> lastTouchedAt = new ConcurrentHashMap<>();
    /** 工具确认截止时间（sessionId → 毫秒时间戳），超时自动取消本轮 */
    private final Map<String, Long> pendingConfirmDeadlines = new ConcurrentHashMap<>();
    /** 挂起回合的收尾回调，供确认超时时复位前端 UI */
    private final Map<String, Runnable> pendingFinishers = new ConcurrentHashMap<>();
    /** 挂起回合的事件下发通道，供确认超时时推送取消提示 */
    private final Map<String, Consumer<StreamEvent>> pendingEmitters = new ConcurrentHashMap<>();

    // ==================== 活跃时间 ====================

    /** 记录会话活跃时间（清扫任务据此判定空闲） */
    public void touch(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            lastTouchedAt.put(sessionId, System.currentTimeMillis());
        }
    }

    /** 返回空闲时间早于 cutoff 的会话（不做过滤判断，由调用方决定是否可清） */
    public List<String> sessionsIdleBefore(long cutoff) {
        return lastTouchedAt.entrySet().stream()
                .filter(e -> e.getValue() < cutoff)
                .map(Map.Entry::getKey)
                .toList();
    }

    // ==================== Workspace 绑定 ====================

    public void bindWorkspace(String sessionId, String workspaceId) {
        sessionWorkspaces.put(sessionId, workspaceId);
    }

    public String workspaceOf(String sessionId) {
        return sessionWorkspaces.get(sessionId);
    }

    /** 返回绑定到指定 workspace 的所有活跃会话 id */
    public List<String> sessionsOfWorkspace(String workspaceId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : sessionWorkspaces.entrySet()) {
            if (workspaceId.equals(e.getValue())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    // ==================== 回合授权 ====================

    /** 追加本回合允许的工具（幂等累加，不覆盖已有授权） */
    public void allowForTurn(String sessionId, Collection<String> toolNames) {
        turnAllowed.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .addAll(toolNames);
    }

    /** 本回合已授权的工具集合（返回副本，避免外部改动内部状态） */
    public Set<String> turnAllowedTools(String sessionId) {
        Set<String> turn = turnAllowed.get(sessionId);
        return turn == null ? Set.of() : new HashSet<>(turn);
    }

    public boolean isAllowedThisTurn(String sessionId, String toolName) {
        Set<String> turn = turnAllowed.get(sessionId);
        return turn != null && turn.contains(toolName);
    }

    // ==================== 待确认工具 ====================

    public void putPendingConfirm(String sessionId, List<ToolUseBlock> tools) {
        pendingConfirms.put(sessionId, tools);
    }

    public List<ToolUseBlock> peekPendingConfirm(String sessionId) {
        return pendingConfirms.get(sessionId);
    }

    /** 取出并清除待确认工具（用户已响应） */
    public List<ToolUseBlock> takePendingConfirm(String sessionId) {
        return pendingConfirms.remove(sessionId);
    }

    public boolean hasPendingConfirm(String sessionId) {
        return pendingConfirms.containsKey(sessionId);
    }

    /**
     * 登记待确认工具，并按配置的确认超时（分钟）设置截止时间。
     * {@code confirmTimeoutMinutes <= 0} 表示不设倒计时（不限时等待）。
     */
    public void armPendingConfirm(String sessionId, List<ToolUseBlock> tools, int confirmTimeoutMinutes) {
        pendingConfirms.put(sessionId, tools);
        if (confirmTimeoutMinutes > 0) {
            pendingConfirmDeadlines.put(sessionId,
                    System.currentTimeMillis() + confirmTimeoutMinutes * 60_000L);
        }
    }

    /** 登记确认超时截止时间与收尾回调（挂起回合的唯一反向出口所需） */
    public void armConfirmTimeout(String sessionId, long deadlineAt,
                                  Runnable finisher, Consumer<StreamEvent> emitter) {
        pendingConfirmDeadlines.put(sessionId, deadlineAt);
        if (finisher != null) {
            pendingFinishers.put(sessionId, finisher);
        }
        if (emitter != null) {
            pendingEmitters.put(sessionId, emitter);
        }
    }

    /** 撤销确认倒计时与挂起回调（用户已响应 / 本轮已终止） */
    public void disarmConfirmTimeout(String sessionId) {
        pendingConfirmDeadlines.remove(sessionId);
        pendingFinishers.remove(sessionId);
        pendingEmitters.remove(sessionId);
    }

    /** 返回确认已超时的会话 id */
    public List<String> expiredConfirmations(long now) {
        return pendingConfirmDeadlines.entrySet().stream()
                .filter(e -> e.getValue() < now)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 摘除截止时间（先摘除可保证后续步骤异常也不会重复触发超时分支） */
    public void clearConfirmDeadline(String sessionId) {
        pendingConfirmDeadlines.remove(sessionId);
    }

    public Runnable takePendingFinisher(String sessionId) {
        return pendingFinishers.remove(sessionId);
    }

    public Consumer<StreamEvent> takePendingEmitter(String sessionId) {
        return pendingEmitters.remove(sessionId);
    }

    // ==================== 流订阅 ====================

    /**
     * 登记本会话的活跃订阅，返回被顶替的旧订阅（调用方负责 dispose）。
     * 保证同一会话只有一个活跃订阅，避免原暂停流与恢复流重复推送事件。
     */
    public Disposable replaceDisposable(String sessionId, Disposable disposable) {
        return sessionDisposables.put(sessionId, disposable);
    }

    /** 登记本会话的活跃订阅（不关心被顶替的旧值时使用） */
    public void setDisposable(String sessionId, Disposable disposable) {
        sessionDisposables.put(sessionId, disposable);
    }

    public Disposable takeDisposable(String sessionId) {
        return sessionDisposables.remove(sessionId);
    }

    /** 清理指定订阅引用（仅当当前登记的就是它，避免误删后继流的句柄） */
    public void clearDisposableIfSame(String sessionId, Disposable expected) {
        sessionDisposables.remove(sessionId, expected);
    }

    public void clearDisposable(String sessionId) {
        sessionDisposables.remove(sessionId);
    }

    /** 是否有正在运行的流 */
    public boolean isRunning(String sessionId) {
        Disposable d = sessionDisposables.get(sessionId);
        return d != null && !d.isDisposed();
    }

    // ==================== 恢复标记 ====================

    /** 标记进入「确认恢复」阶段：恢复流统一负责发送 end */
    public void markResuming(String sessionId) {
        resuming.put(sessionId, new AtomicBoolean(true));
    }

    /** 只读查询：是否正处于「确认恢复」阶段（不消费标记） */
    public boolean isResuming(String sessionId) {
        AtomicBoolean flag = resuming.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * 消费恢复标记：若处于恢复阶段则消费掉并返回 true（表示本次 end 应被抑制）。
     * CAS 保证同一标记只被消费一次。
     */
    public boolean consumeResumingFlag(String sessionId) {
        AtomicBoolean flag = resuming.get(sessionId);
        return flag != null && flag.compareAndSet(true, false);
    }

    public void clearResuming(String sessionId) {
        resuming.remove(sessionId);
    }

    // ==================== 护栏计数 ====================

    /**
     * 递增子 Agent 调度次数并返回累计值（含本次）。
     * 用于识别「同一子 Agent 被反复调度」的循环编排。
     * <p>
     * 注意 {@code subagentName} 必须是子 Agent 身份（声明名 agent_id），不是工具名。
     * 传工具名会把「并行派发多个不同子 Agent」误计为同一个，从而误伤 team 模式。
     */
    public int recordSubagentCall(String sessionId, String subagentName) {
        return subagentCallCounts
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .merge(subagentName, 1, Integer::sum);
    }

    /**
     * 标记某子 Agent 已完成一次交付（其派发工具的结果已返回）。
     * <p>
     * 「循环调度」的判据不是次数多，而是<b>拿到结果后仍反复重派同一个子 Agent</b>：
     * team 模式同一轮并发派 5 个是健康的并行，此时一个结果都还没回；
     * 而串行地「派→拿结果→再派同一个」才是病态循环。故计数只在已有交付记录时才追责。
     */
    public void markSubagentDelivered(String sessionId, String subagentName) {
        subagentDelivered
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(subagentName);
    }

    /** 该子 Agent 在本会话是否已交付过结果（用于区分并行批次与串行重派） */
    public boolean hasSubagentDelivered(String sessionId, String subagentName) {
        Set<String> delivered = subagentDelivered.get(sessionId);
        return delivered != null && delivered.contains(subagentName);
    }

    /** 递增指定工具的连续失败次数并返回累计值（含本次） */
    public int recordToolFailure(String sessionId, String toolName) {
        return toolFailCounts
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .merge(toolName, 1, Integer::sum);
    }

    /** 工具执行成功：清零该工具的连续失败计数（"连续"语义的关键） */
    public void resetToolFailure(String sessionId, String toolName) {
        Map<String, Integer> counts = toolFailCounts.get(sessionId);
        if (counts != null) {
            counts.remove(toolName);
        }
    }

    // ==================== 子 Agent 结果缓冲 ====================

    /**
     * 累积子 Agent 工具结果文本，超过 {@value #MAX_SUBAGENT_BUFFER_CHARS} 字符后停止追加
     * 并附截断标记，避免超长工具输出（如全量日志）撑爆内存。
     */
    public void appendSubagentResult(String bufKey, String delta) {
        StringBuilder sb = subagentResultBuffers.computeIfAbsent(bufKey, k -> new StringBuilder());
        if (sb.length() < MAX_SUBAGENT_BUFFER_CHARS) {
            sb.append(delta);
            if (sb.length() >= MAX_SUBAGENT_BUFFER_CHARS) {
                sb.append("\n…（输出过长已截断）");
            }
        }
    }

    /** 取出并清除累积的子 Agent 结果文本 */
    public String takeSubagentResult(String bufKey) {
        StringBuilder buf = subagentResultBuffers.remove(bufKey);
        return buf == null ? "" : buf.toString();
    }

    public void clearSubagentResult(String bufKey) {
        subagentResultBuffers.remove(bufKey);
    }

    /**
     * 登记挂起回合的收尾回调与事件通道。null 参数被忽略（不覆盖已有登记），
     * 由 {@link #takePendingFinisher}/{@link #takePendingEmitter} 消费。
     */
    public void registerPendingCallbacks(String sessionId, Runnable onFinish, Consumer<StreamEvent> onEvent) {
        if (onFinish != null) {
            pendingFinishers.put(sessionId, onFinish);
        }
        if (onEvent != null) {
            pendingEmitters.put(sessionId, onEvent);
        }
    }

    // ==================== 生命周期入口 ====================


    /**
     * 新一轮用户消息开始：重置「按回合累计」的护栏计数与恢复标记。
     * <p>
     * 计数必须按回合归零，否则跨回合累加会让长会话在第 N 轮被误判为循环调度。
     */
    public void beginTurn(String sessionId) {
        subagentCallCounts.remove(sessionId);
        subagentDelivered.remove(sessionId);
        toolFailCounts.remove(sessionId);
        resuming.remove(sessionId);
    }

    /**
     * 主回合正常结束：清空「本回合」级状态。
     * <p>
     * 只清 {@code turnAllowed} —— 回合授权绝不能跨回合存活，否则后续写类工具不再弹窗确认。
     * 其余状态（workspace 绑定、活跃时间）需跨回合保留。
     */
    public void endTurn(String sessionId) {
        turnAllowed.remove(sessionId);
    }

    /**
     * 用户主动停止本轮：清理本轮执行态与挂起确认，但**保留** RetryScope 的 abort 标记
     * （正在退避等待的重试需要读取它），标记由下一次对话或 {@link #release} 清除。
     *
     * @return 被摘除的订阅句柄，调用方负责 dispose
     */
    public Disposable abortTurn(String sessionId) {
        Disposable disp = sessionDisposables.remove(sessionId);
        pendingConfirms.remove(sessionId);
        turnAllowed.remove(sessionId);
        resuming.remove(sessionId);
        toolFailCounts.remove(sessionId);
        disarmConfirmTimeout(sessionId);
        return disp;
    }

    /**
     * 会话状态整体驱逐（连接断开 / 会话删除 / 空闲超时）：清空该会话的全部条目。
     * <p>
     * 必须清 {@code turnAllowed} 与 {@code sessionWorkspaces}：残留的工具授权若被
     * 复用的 sessionId 继承，新会话会绕过确认弹窗（权限问题而非单纯内存问题）。
     *
     * @return 被摘除的订阅句柄，调用方负责 dispose
     */
    public Disposable release(String sessionId) {
        Disposable disp = sessionDisposables.remove(sessionId);
        pendingConfirms.remove(sessionId);
        turnAllowed.remove(sessionId);
        sessionWorkspaces.remove(sessionId);
        subagentCallCounts.remove(sessionId);
        subagentDelivered.remove(sessionId);
        toolFailCounts.remove(sessionId);
        resuming.remove(sessionId);
        lastTouchedAt.remove(sessionId);
        disarmConfirmTimeout(sessionId);
        // 防止 RetryScope 的 abort 标记随已驱逐会话无界堆积
        RetryScope.clear(sessionId);
        // 子 Agent 结果缓冲以 "sessionId::instanceKey::toolCallId" 为 key，按前缀清理本会话的
        // 残留条目。key 含 toolCallId 维度后，若某次 ToolResultEnd 事件丢失，该条目不会再被
        // 后续调用覆盖清理，只能靠这里的前缀兜底回收 —— 故此前缀清理不可删。
        String prefix = sessionId + "::";
        subagentResultBuffers.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("已释放会话内存状态: sessionId={}", sessionId);
        return disp;
    }

    /** 诊断用：当前驻留的会话状态条目数 */
    public int trackedSessionCount() {
        return lastTouchedAt.size();
    }
}
