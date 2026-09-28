package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.ExecutionContext;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.base.orchestration.OrchestrationPlan;
import com.xinl.easyclaw.base.orchestration.OrchestrationResult;
import com.xinl.easyclaw.base.orchestration.StepExecutor;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 编排模式注册表（Spring 侧门面）。
 * <p>
 * <b>存在的意义</b>：消灭散落各处的 {@code "team".equals(scenario.getMode())} 硬编码。
 * 那些判断是「新增一个模式要改公共代码」的病根 —— 每加一种编排方式，就要在
 * 提示词构建、场景校验、场景解析等多处补一个分支，漏改一处就是行为不一致。
 * <p>
 * <b>为什么只是门面而不自己加载</b>：需要模式元数据的调用方里，有一部分是静态工具类
 * （{@code OrchestrationPromptBuilder}、{@code ScenarioBinding}），它们注入不了 Bean。
 * 真正的 SPI 发现放在 base 的 {@link OrchestrationModes}，本类只做 Bean 包装 + 启动校验，
 * 保证 Spring 侧与静态侧看到的是<b>同一份</b>模式集合，不会出现两套结论。
 *
 * @see OrchestrationModes 真正的 SPI 发现入口
 */
@Component
public class OrchestratorRegistry {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorRegistry.class);

    /** 存量数据里 mode 为空时的默认模式 */
    public static final String DEFAULT_MODE = OrchestrationModes.DEFAULT_MODE;

    /**
     * 启动期把发现结果打进日志并校验必备模式。
     * <p>
     * ServiceLoader 的失败方式是「静默返回空集合」—— 打包方式变化、
     * {@code META-INF/services} 丢失都不会报错，只会在运行时表现为「所有场景都降级」。
     * 在启动时 fail-fast，比让用户在对话里发现编排失效要好。
     */
    @PostConstruct
    void logAndVerify() {
        log.info("[SPI] 已发现 {} 个编排模式: {}",
                OrchestrationModes.size(), OrchestrationModes.modeIds());
        if (OrchestrationModes.find(DEFAULT_MODE).isEmpty()) {
            throw new IllegalStateException(
                    "缺少默认编排模式 [" + DEFAULT_MODE + "]，classpath 未包含 mode-single 模块；"
                            + "已发现: " + OrchestrationModes.modeIds());
        }
    }

    /** 按 modeId 查找，找不到返回空 */
    public Optional<AgentOrchestrator> find(String modeId) {
        return OrchestrationModes.find(modeId);
    }

    /**
     * 解析场景对应的编排模式，找不到时降级为 single。
     * <p>
     * <b>为什么降级而非抛异常</b>：DB 里的 mode 是历史数据，可能存着某个已被删除的
     * 模式 id。让用户因为一条旧数据完全无法对话，比按单智能体跑一次糟糕得多。
     * 降级会打 warn 日志，便于发现脏数据。
     */
    public AgentOrchestrator resolve(ScenarioProfile scenario) {
        String modeId = (scenario == null) ? null : scenario.getMode();
        Optional<AgentOrchestrator> hit = OrchestrationModes.find(modeId);
        if (hit.isPresent()) {
            return hit.get();
        }
        if (modeId != null && !modeId.isBlank()) {
            log.warn("[SPI] 场景 mode=[{}] 未注册，已降级为 {}", modeId, DEFAULT_MODE);
        }
        return OrchestrationModes.find(DEFAULT_MODE).orElseThrow(() ->
                new IllegalStateException("未注册任何编排模式，classpath 缺少 mode-single 模块"));
    }

    /**
     * 一步到位：解析上下文对应的模式并产出执行计划。
     * <p>
     * 这是 api 层唯一需要调用的入口 —— 调用方无需知道有几种模式、
     * 也无需为每种模式写分支。
     */
    public OrchestrationPlan plan(ExecutionContext ctx) {
        return resolve(ctx == null ? null : ctx.scenario()).plan(ctx);
    }

    /**
     * 一步到位：解析模式、产出计划并执行。
     * <p>
     * <b>这是接通「编排 → 执行」的总入口</b>。调用方（api 层）只需提供
     * {@link StepExecutor} 说明「怎么真的跑一个 step」，具体的编排语义
     * （顺序、并发、门禁、返工）由各 mode 自己决定，公共层不做分支。
     *
     * @param ctx      执行上下文
     * @param executor 步骤执行器，由 api 层实现
     * @return 执行结果；计划不可执行时返回带 errors 的结果
     */
    public CompletableFuture<OrchestrationResult> execute(ExecutionContext ctx, StepExecutor executor) {
        AgentOrchestrator orchestrator = resolve(ctx == null ? null : ctx.scenario());
        // 回合级模式可见性：排查「到底走了哪个模式」时以本条为准（每回合一条，不刷屏）
        log.info("[编排] 模式选定: mode={}, orchestrator={}, workspaceId={}, sessionId={}",
                orchestrator.modeId(), orchestrator.getClass().getSimpleName(),
                ctx == null ? "-" : ctx.workspaceId(), ctx == null ? "-" : ctx.sessionId());
        return orchestrator.execute(ctx, executor);
    }

    /**
     * 该场景是否需要多智能体编排（即执行计划可能不止一步）。
     * <p>
     * 替代散落各处的 {@code "team".equals(mode)} 判断。判据是「模式不是 single」
     * 而非「模式等于 team」—— 这样新增 schedule 等模式时无需再改这里。
     */
    public boolean isOrchestrated(ScenarioProfile scenario) {
        return scenario != null && OrchestrationModes.isOrchestrated(scenario.getMode());
    }

    /** 全部已注册的 modeId */
    public List<String> modeIds() {
        return OrchestrationModes.modeIds();
    }

    /** 全部已注册的编排模式 */
    public Collection<AgentOrchestrator> all() {
        return OrchestrationModes.all();
    }

    public int size() {
        return OrchestrationModes.size();
    }
}
