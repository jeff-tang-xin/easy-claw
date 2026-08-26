package com.xinl.easyclaw.config;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话维度的「放弃重试」开关。
 * <p>
 * 取代 {@link RetryableHttpTransport} 早期的进程级 {@code abortAll/resetAll}：那种做法下
 * {@code stopChat(A)} 会把 B 会话正在退避等待的重试一并中止，而随后 {@code streamChat(B)}
 * 的重置又会抹掉 A 的停止意图——并发多会话时「停止」语义完全错乱。
 * <p>
 * 会话标识通过 Reactor Context 以 {@link #CONTEXT_KEY} 向下游传递（反应式链路中 ThreadLocal
 * 不可靠）；非反应式的阻塞 {@code execute()} 路径退化为线程级 {@link #CURRENT} 兜底。
 */
public final class RetryScope {

    /** Reactor Context 中承载 sessionId 的 key */
    public static final String CONTEXT_KEY = "easyclaw.retry.sessionId";

    /** 已请求放弃重试的会话集合；条目由 stopChat 写入、streamChat/releaseSession 清除 */
    private static final Set<String> ABORTED = ConcurrentHashMap.newKeySet();

    /** 阻塞路径兜底：execute() 无法读取 Reactor Context，只能依赖调用线程标记 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RetryScope() {
    }

    /** 标记该会话放弃后续重试（幂等） */
    public static void abort(String sessionId) {
        if (sessionId != null) {
            ABORTED.add(sessionId);
        }
    }

    /** 清除标记：新回合开始或会话状态驱逐时调用，避免 {@link #ABORTED} 无界增长 */
    public static void clear(String sessionId) {
        if (sessionId != null) {
            ABORTED.remove(sessionId);
        }
    }

    public static boolean isAborted(String sessionId) {
        return sessionId != null && ABORTED.contains(sessionId);
    }

    /**
     * 绑定当前线程的会话标识，仅供无法传递 Reactor Context 的阻塞调用使用。
     * 必须在 finally 中调用 {@link #unbindCurrent()}，否则线程池复用会把标识串给别的会话。
     */
    public static void bindCurrent(String sessionId) {
        CURRENT.set(sessionId);
    }

    public static void unbindCurrent() {
        CURRENT.remove();
    }

    public static String currentSessionId() {
        return CURRENT.get();
    }

    /** 仅用于测试/诊断：当前被标记放弃的会话数 */
    public static int abortedCount() {
        return ABORTED.size();
    }

    /** 仅用于测试：清空全部标记 */
    static void reset() {
        ABORTED.clear();
    }

    /** 供诊断打印，避免外部直接持有可变集合 */
    static Map<String, Boolean> snapshot() {
        Map<String, Boolean> m = new ConcurrentHashMap<>();
        ABORTED.forEach(s -> m.put(s, Boolean.TRUE));
        return m;
    }
}
