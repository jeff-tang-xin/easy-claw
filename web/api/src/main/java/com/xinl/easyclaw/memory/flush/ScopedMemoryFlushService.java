package com.xinl.easyclaw.memory.flush;

import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 全量上下文的记忆提取执行器：载荷采用 vendored 原生的「整段上下文」语义
 * （state.getContext() 快照全量喂给提取模型），但触发与执行由 web 侧接管，规避
 * vendored MemoryFlushMiddleware 的「concatWith 拖住主流直至提取完成」缺陷
 * （用户实测「莫名其妙卡在提取」）。
 *
 * <p>两个核心机制：
 * <ol>
 *   <li><b>全量载荷</b>：每次提取都喂入当前上下文的完整快照（对齐 vendored 原生
 *       MemoryFlushManager 的用法），由提取 prompt 的去重指示兜底重复观察，
 *       不再做游标增量切片——载荷随上下文窗（用户已调至 100K tokens）有界；</li>
 *   <li><b>异步执行</b>：flushAsyncEnabled=true 时提交到单线程守护 executor，主流事件
 *       立即 complete，回合收尾零拖延；false 时退化为同步等待（对照/调试档）。
 * </ol>
 *
 * <p>提取与落盘复用 vendored {@link MemoryFlushManager}（写同一个 daily ledger，
 * 保证 vendored consolidation 能读到并合并进 MEMORY.md）。
 *
 * <p>vendored 侧 flush 已由 MemorySettingsService.toMemoryConfig 置 NEVER，不重复触发。
 * 会话级串行：全局单线程 executor + pending 去重，同会话 flush 绝不并发/积压；
 * 上一轮全量提取未结束时新一轮被 pending 跳过（消息不丢——下轮全量快照仍覆盖）。
 */
@Service
public class ScopedMemoryFlushService {

    private static final Logger log = LoggerFactory.getLogger(ScopedMemoryFlushService.class);

    /** 单次提取模型调用的兜底时限（异步线程内 block，防模型挂起占死 executor）。 */
    private static final Duration FLUSH_CALL_TIMEOUT = Duration.ofMinutes(5);

    /** 会话上次 flush 发起时刻（throttled 节流）。 */
    private final Map<String, Instant> lastFlushAt = new ConcurrentHashMap<>();
    /** 进行中/排队中的会话键（防同会话积压）。 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scoped-memory-flush");
                t.setDaemon(true);
                return t;
            });

    /** 提取执行点（包私有注入便于单测）；生产实现走 vendored MemoryFlushManager。 */
    interface FlushRunner {
        void run(Agent agent, RuntimeContext rc, Model model, List<Msg> payload) throws Exception;
    }

    private final FlushRunner runner;

    public ScopedMemoryFlushService() {
        this(ScopedMemoryFlushService::runWithVendoredFlushManager);
    }

    ScopedMemoryFlushService(FlushRunner runner) {
        this.runner = runner;
    }

    /**
     * 异步提交一次全量提取（flushAsyncEnabled=true 路径）：节流 + 防重后立即返回，
     * 提取在守护线程内执行，任何失败只记日志、绝不波及调用方。
     */
    public void submitScopedFlush(String key, Agent agent, RuntimeContext rc,
                                  MemorySettingsEntity settings, Model model) {
        if (!claimSlot(key, settings)) {
            return;
        }
        executor.submit(() -> {
            try {
                doFlush(key, agent, rc, settings, model);
            } catch (Throwable t) {
                log.warn("记忆提取后台执行失败（不影响主流程）: session={}, err={}",
                        key, t.toString());
            } finally {
                pending.remove(key);
            }
        });
    }

    /**
     * 同步执行一次全量提取（flushAsyncEnabled=false 路径）：由 middleware concatWith
     * 等待，语义对齐 vendored 同步 flush，但模型调用有兜底时限。
     */
    public void runFlushBlocking(String key, Agent agent, RuntimeContext rc,
                                 MemorySettingsEntity settings, Model model) {
        if (!claimSlot(key, settings)) {
            return;
        }
        try {
            doFlush(key, agent, rc, settings, model);
        } catch (Throwable t) {
            log.warn("记忆提取失败（同步模式，已吞掉不影响回合收尾）: session={}, err={}",
                    key, t.toString());
        } finally {
            pending.remove(key);
        }
    }

    /** throttled 节流 + pending 防重；always 模式跳过节流。返回 true 表示本轮可执行。 */
    private boolean claimSlot(String key, MemorySettingsEntity settings) {
        String mode = settings.getFlushMode() == null ? "always" : settings.getFlushMode();
        if (!"always".equals(mode)) {
            int minGapMinutes = settings.getFlushMinGapMinutes() == null
                    ? 30 : settings.getFlushMinGapMinutes();
            Instant last = lastFlushAt.get(key);
            if (last != null
                    && Duration.between(last, Instant.now()).compareTo(
                            Duration.ofMinutes(minGapMinutes)) < 0) {
                return false;
            }
            lastFlushAt.put(key, Instant.now());
        }
        return pending.add(key);
    }

    private void doFlush(String key, Agent agent, RuntimeContext rc,
                         MemorySettingsEntity settings, Model model) throws Exception {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return;
        }
        // 快照：异步执行时下一回合可能正在写 context
        List<Msg> payload = List.copyOf(state.getContext());
        if (payload.isEmpty()) {
            return;
        }
        log.info("记忆提取开始（全量上下文）: session={}, 上下文 {} 条", key, payload.size());
        runner.run(agent, rc, model, payload);
        log.info("记忆提取完成（全量上下文）: session={}, 上下文 {} 条", key, payload.size());
    }

    /** 生产提取实现：复用 vendored MemoryFlushManager（写同一 daily ledger）。 */
    private static void runWithVendoredFlushManager(
            Agent agent, RuntimeContext rc, Model model, List<Msg> payload) {
        if (!(agent instanceof HarnessAgent harnessAgent)) {
            throw new IllegalStateException(
                    "ScopedMemoryFlush 仅支持 HarnessAgent，实际: " + agent.getClass().getName());
        }
        new MemoryFlushManager(harnessAgent.getWorkspaceManager(), model, null)
                .flushMemories(rc, payload)
                .block(FLUSH_CALL_TIMEOUT);
    }

    /** 会话键解析：sessionId 优先，空则 userId，再空常量。 */
    public static String flushKey(RuntimeContext rc) {
        if (rc != null) {
            if (rc.getSessionId() != null && !rc.getSessionId().isBlank()) {
                return rc.getSessionId();
            }
            if (rc.getUserId() != null && !rc.getUserId().isBlank()) {
                return rc.getUserId();
            }
        }
        return "default";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
