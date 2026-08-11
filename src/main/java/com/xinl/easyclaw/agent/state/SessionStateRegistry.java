package com.xinl.easyclaw.agent.state;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态注册表（抽离自 AgentService 6 个 Map 字段的合并容器）。
 * <p>
 * 行为约束（必须与原 AgentService 一致）：
 * <ul>
 *   <li>getOrCreate 永远返回非 null（computeIfAbsent 原子创建）</li>
 *   <li>remove 必须调用 state.dispose() —— 防内存泄漏（Disposable 未释放）</li>
 *   <li>整体并发安全（ConcurrentHashMap 容器）</li>
 * </ul>
 *
 * @see SessionState
 */
@Component
public class SessionStateRegistry {

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话状态（原子操作）
     */
    public SessionState getOrCreate(String sessionId) {
        return states.computeIfAbsent(sessionId, SessionState::new);
    }

    /**
     * 仅查询（不创建）。返回 null 表示会话未注册。
     */
    public SessionState get(String sessionId) {
        return states.get(sessionId);
    }

    /**
     * 移除会话状态 + 触发 dispose（防 Disposable 泄漏）。
     */
    public SessionState remove(String sessionId) {
        SessionState s = states.remove(sessionId);
        if (s != null) {
            s.dispose();
        }
        return s;
    }

    public boolean exists(String sessionId) {
        return states.containsKey(sessionId);
    }

    /** 当前活跃会话数（调试/管理用） */
    public int size() {
        return states.size();
    }

    /** 所有活跃会话 ID（只读视图，调试用） */
    public Collection<String> sessionIds() {
        return states.keySet();
    }
}
