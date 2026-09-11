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
import java.util.ArrayList;
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
 * 框定范围的记忆提取执行器：替代 vendored MemoryFlushMiddleware 的
 * 「全量上下文载荷 + concatWith 拖住主流」模式（记忆累积后提取载荷 13-16 万字符，
 * 回合收尾被提取调用绑架挂起——用户实测「莫名其妙卡在提取」）。
 *
 * <p>三个核心机制：
 * <ol>
 *   <li><b>框定范围</b>：按会话游标只提取「自上次提取以来的增量消息」，单次最多
 *       window 条 + background 条背景（{@link FlushSlicePlanner}），载荷有界不随记忆增长；</li>
 *   <li><b>异步执行</b>：flushAsyncEnabled=true 时提交到单线程守护 executor，主流事件
 *       立即 complete，回合收尾零拖延；false 时退化为同步等待（对照/调试档）；</li>
 *   <li><b>游标单调推进</b>：仅提取成功后推进；失败不推进、下轮重试；增量超窗只取
 *       最早一批，剩余留待下轮——不丢消息，只延迟。
 * </ol>
 *
 * <p>提取与落盘复用 vendored {@link MemoryFlushManager}（写同一个 daily ledger，
 * 保证 vendored consolidation 能读到并合并进 MEMORY.md）；daily 去重由提取 prompt
 * 既有指示兜底，背景消息重复提取无害。
 *
 * <p>vendored 侧 flush 已由 MemorySettingsService.toMemoryConfig 置 NEVER，不重复触发。
 * 会话级串行：全局单线程 executor + pending 去重，同会话 flush 绝不并发/积压。
 */
@Service
public class ScopedMemoryFlushService {

    private static final Logger log = LoggerFactory.getLogger(ScopedMemoryFlushService.class);

    /** 单次提取模型调用的兜底时限（异步线程内 block，防模型挂起占死 executor）。 */
    private static final Duration FLUSH_CALL_TIMEOUT = Duration.ofMinutes(5);

    /** 会话游标：已提取到的消息下标（不含）。重启丢失——首轮回退提取最近窗口，无害。 */
    private final Map<String, Integer> cursors = new ConcurrentHashMap<>();
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
     * 异步提交一次框定提取（flushAsyncEnabled=true 路径）：节流 + 防重后立即返回，
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
     * 同步执行一次框定提取（flushAsyncEnabled=false 路径）：由 middleware concatWith
     * 等待，语义对齐 vendored 同步 flush，但载荷已框定、模型调用有兜底时限。
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
        String mode = settings.getFlushMode() == null ? "throttled" : settings.getFlushMode();
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
        List<Msg> context = List.copyOf(state.getContext());
        if (context.isEmpty()) {
            return;
        }
        int cursor = cursors.getOrDefault(key, 0);
        int window = settings.getFlushWindowMessages() == null
                ? 10 : settings.getFlushWindowMessages();
        int background = settings.getFlushBackgroundMessages() == null
                ? 5 : settings.getFlushBackgroundMessages();
        FlushSlicePlanner.Slice slice =
                FlushSlicePlanner.plan(context.size(), cursor, window, background);
        if (slice.isEmpty()) {
            // 游标对齐（含压缩后无新增量的情形）
            cursors.put(key, context.size());
            return;
        }
        List<Msg> payload = new ArrayList<>(
                context.subList(slice.backgroundFrom(), slice.to()));
        log.info("记忆提取开始: session={}, 新消息 {} 条 + 背景 {} 条（上下文共 {} 条，游标 {}→{}）",
                key, slice.newCount(), slice.from() - slice.backgroundFrom(),
                context.size(), cursor, slice.to());
        runner.run(agent, rc, model, payload);
        cursors.put(key, slice.to());
        log.info("记忆提取完成: session={}, 游标推进至 {}", key, slice.to());
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

    /** 单测探针：读取会话游标。 */
    int cursorOf(String key) {
        return cursors.getOrDefault(key, 0);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
