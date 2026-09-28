package com.xinl.easyclaw.memory.flush;

import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import com.xinl.easyclaw.memory.settings.MemorySettingsService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 全量上下文的记忆提取中间件：回合结束后按「全量上下文 + 同步/异步开关」触发提取，
 * 接管 vendored MemoryFlushMiddleware（其 trigger 已置 NEVER，注册但永不触发）。
 *
 * <p>与 vendored 的唯一执行期差异在<b>触发方式</b>，载荷语义保持 vendored 原生：
 * <ol>
 *   <li><b>载荷同源</b>：vendored 每次把「全量上下文 + MEMORY.md 全文 + 当日流水全文」
 *       塞给提取模型；本类同样喂入 state.getContext() 的完整快照（上下文窗已调至
 *       100K tokens，载荷随窗有界），再复用同一个 MemoryFlushManager 落盘——
 *       见 {@link ScopedMemoryFlushService}。</li>
 *   <li><b>异步可选</b>：vendored 以 concatWith 把提取串进主流，提取不完回合不收尾；
 *       本类 flushAsyncEnabled=true（默认）时 doOnComplete 提交守护线程即返回，
 *       主流立即 complete；false 时保留 concatWith 同步等待语义（对照档）。
 * </ol>
 *
 * <p>flushMode 语义由本类消费：never 不挂任何触发（主流原样返回）；
 * always/throttled 的节流由 {@link ScopedMemoryFlushService} 判定。
 * 设置每回合实时读取，修改无需重建 Agent 即可生效（装配层 PUT 后也会重建，双保险）。
 */
public class ScopedMemoryFlushMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ScopedMemoryFlushMiddleware.class);

    private final ScopedMemoryFlushService flushService;
    private final MemorySettingsService settingsService;
    private final Model model;

    public ScopedMemoryFlushMiddleware(ScopedMemoryFlushService flushService,
                                       MemorySettingsService settingsService,
                                       Model model) {
        this.flushService = flushService;
        this.settingsService = settingsService;
        this.model = model;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Flux<AgentEvent> main = next.apply(input);

        MemorySettingsEntity settings;
        try {
            settings = settingsService.getOrCreate();
        } catch (Exception e) {
            // 设置读取失败绝不弄死主流——跳过本轮提取
            log.warn("记忆设置读取失败，跳过本轮提取: {}", e.toString());
            return main;
        }
        String mode = settings.getFlushMode() == null ? "always" : settings.getFlushMode();
        if ("never".equals(mode)) {
            return main;
        }

        String key = ScopedMemoryFlushService.flushKey(rc);
        if (Boolean.TRUE.equals(settings.getFlushAsyncEnabled())) {
            // 异步：回合事件流不受提取影响，收尾零拖延
            return main.doOnComplete(
                    () -> flushService.submitScopedFlush(key, agent, rc, settings, model));
        }
        // 同步：保留 vendored 等待语义，但执行有兜底时限与异常吞咽
        return main.concatWith(
                Mono.defer(() -> {
                            flushService.runFlushBlocking(key, agent, rc, settings, model);
                            return Mono.<AgentEvent>empty();
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
