package com.xinl.easyclaw.agent.state;

import io.agentscope.core.message.ToolUseBlock;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个会话的完整状态（抽离自 AgentService 6 个 Map 字段的合并 holder）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>并发：pendingConfirms 涉及"检查 + 改 + 通知"复合操作，用 synchronized 块封装（保持原 AgentService 行 916 put / 行 712 remove 的同步语义）</li>
 *   <li>其他 5 个字段保留 ConcurrentHashMap / newKeySet / volatile，行为完全不变</li>
 *   <li>dispose() 在 registry.remove 时被调用，清理 Disposable + 6 个内部容器，防止内存泄漏</li>
 * </ul>
 *
 * @see SessionStateRegistry
 */
public class SessionState {

    private final String sessionId;

    /** 待用户确认的工具调用（按会话隔离）—— 同步保护 */
    private final List<ToolUseBlock> pendingConfirms = new ArrayList<>();

    /** 本回合已允许的工具名（按会话隔离）—— 高频 add，保留 newKeySet */
    private final Set<String> turnAllowed = ConcurrentHashMap.newKeySet();

    /** 会话 → Workspace 映射（权限判断按 workspace 隔离） —— write 少 read 多，volatile 即可 */
    private volatile String workspaceId;

    /** 子 Agent 调度计数（subagentName → 次数），用于循环调度防护 */
    private final Map<String, Integer> subagentCallCounts = new ConcurrentHashMap<>();

    /** 子 Agent 工具结果文本缓冲（source → 累积文本），用于 end 时推断真实状态 */
    private final Map<String, StringBuilder> subagentResultBuffers = new ConcurrentHashMap<>();

    /** 当前会话正在运行的流订阅（用于 stopChat 时 dispose 取消） */
    private volatile Disposable activeDisposable;

    public SessionState(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    // ============ pendingConfirms 状态机 ============

    public synchronized void setPendingConfirms(List<ToolUseBlock> tools) {
        this.pendingConfirms.clear();
        if (tools != null) {
            this.pendingConfirms.addAll(tools);
        }
    }

    public synchronized List<ToolUseBlock> drainPendingConfirms() {
        List<ToolUseBlock> copy = new ArrayList<>(this.pendingConfirms);
        this.pendingConfirms.clear();
        return copy;
    }

    public synchronized boolean hasPendingConfirms() {
        return !this.pendingConfirms.isEmpty();
    }

    // ============ turnAllowed ============

    public void allowTurn(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        this.turnAllowed.addAll(toolNames);
    }

    public boolean isTurnAllowed(String toolName) {
        return toolName != null && this.turnAllowed.contains(toolName);
    }

    public void clearTurnAllowed() {
        this.turnAllowed.clear();
    }

    // ============ session ↔ workspace ============

    public void bindWorkspace(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String workspaceId() {
        return this.workspaceId;
    }

    // ============ subagentCallCounts（循环防护） ============

    public int incrementSubagentCall(String subName) {
        return this.subagentCallCounts.merge(subName, 1, Integer::sum);
    }

    public void resetSubagentCounts() {
        this.subagentCallCounts.clear();
    }

    // ============ subagentResultBuffers（end 时推断真实状态用） ============

    public StringBuilder subagentBuffer(String source) {
        return this.subagentResultBuffers.computeIfAbsent(source, k -> new StringBuilder());
    }

    public String removeSubagentBuffer(String source) {
        StringBuilder sb = this.subagentResultBuffers.remove(source);
        return sb == null ? "" : sb.toString();
    }

    // ============ Disposable 生命周期 ============

    public void setActiveDisposable(Disposable d) {
        this.activeDisposable = d;
    }

    public Disposable activeDisposable() {
        return this.activeDisposable;
    }

    public Disposable clearActiveDisposable() {
        Disposable d = this.activeDisposable;
        this.activeDisposable = null;
        return d;
    }

    // ============ 生命周期终止（registry.remove 时调用） ============

    public void dispose() {
        Disposable d = clearActiveDisposable();
        if (d != null && !d.isDisposed()) {
            try {
                d.dispose();
            } catch (Exception ignore) {
                // dispose 失败不抛——registry.remove 不应被单个会话的清理阻塞
            }
        }
        synchronized (this) {
            this.pendingConfirms.clear();
        }
        this.turnAllowed.clear();
        this.subagentCallCounts.clear();
        this.subagentResultBuffers.clear();
    }
}
