package com.xinl.easyclaw.base.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 编排模式的 SPI 发现入口（纯 JDK，无 Spring 依赖）。
 * <p>
 * <b>为什么放在 base 而不是 agent-core</b>：需要用到模式元数据的地方里，有相当一部分是
 * <b>静态工具类</b>（{@code OrchestrationPromptBuilder}、{@code ScenarioBinding}），
 * 它们注入不了 Spring Bean。若只在 agent-core 提供 Bean 形态的注册表，
 * 这些静态类就只能继续硬编码 {@code "team".equals(...)} —— 病根消不掉。
 * <p>
 * 因此把发现逻辑做成 base 层的静态入口，Spring 侧的
 * {@code OrchestratorRegistry} 只是它的一层 Bean 包装，两者共享同一份数据，
 * 不会出现「Bean 里有 3 个模式、静态类里只认 team」的分裂。
 * <p>
 * <b>加载时机</b>：类初始化时一次性加载并固化。{@code ServiceLoader.load} 每次调用都会
 * 重新实例化，若每次查询都 load，不仅有开销，同一 modeId 还会拿到不同实例。
 * <p>
 * <b>DB 兼容红线</b>：{@code ScenarioEntity.mode} 存的是 {@code "single"}/{@code "team"}
 * 字面量，SQLite {@code ddl-auto: update} 不迁移历史值，这两个 id 不可改名。
 */
public final class OrchestrationModes {

    /** 存量数据 mode 为空、或指向已下线模式时的兜底 */
    public static final String DEFAULT_MODE = "single";

    private static final Map<String, AgentOrchestrator> MODES = discover();

    private OrchestrationModes() {
    }

    private static Map<String, AgentOrchestrator> discover() {
        Map<String, AgentOrchestrator> found = new LinkedHashMap<>();
        for (AgentOrchestrator orchestrator : ServiceLoader.load(AgentOrchestrator.class)) {
            String id;
            try {
                id = orchestrator.modeId();
            } catch (Exception e) {
                // 这里不能用 slf4j：base 层是契约层，保持零日志依赖。
                // 能走到这一步说明实现本身有 bug，交由上层注册表在启动时报出。
                continue;
            }
            if (id != null && !id.isBlank()) {
                found.putIfAbsent(id.trim(), orchestrator);
            }
        }
        return Map.copyOf(found);
    }

    /** 按 modeId 精确查找 */
    public static Optional<AgentOrchestrator> find(String modeId) {
        if (modeId == null || modeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(MODES.get(modeId.trim()));
    }

    /**
     * 该模式是否需要多智能体编排（执行计划可能多于一步，且步骤来自场景预配置的 workflow）。
     * <p>
     * <b>替代散落各处的 {@code "team".equals(mode)}</b>。判据是模式自声明
     * {@link AgentOrchestrator#requiresWorkflowSteps()}（默认「已注册且不是 single」）——
     * 这样新增 schedule 等编排型模式时，所有调用点自动生效，无需再逐处补分支；
     * 而 ops 这类单执行体模式（计划恒为单阶段单步、不消费 workflow）覆写返回 false，
     * 不会被「编排型才有的配置校验 / 直派 / 审计」误伤。
     * <p>
     * 未注册的 mode 视为非编排（走单智能体兜底），与 {@code resolveOrDefault} 的降级一致。
     * <p>
     * <b>降级是静默的，请勿依赖本方法发现脏数据</b>：mode 写错（如 {@code "Team"} 大小写不符、
     * 或指向已下线模式）时这里只会返回 false，用户看到的现象是「团队模式配了却按单智能体跑」，
     * 而非报错。要感知这种情况请调用 {@link #isUnregistered(String)} —— 它把
     * 「显式配了一个不认识的 mode」与「本来就是 single」区分开，调用方可据此打告警。
     */
    public static boolean isOrchestrated(String modeId) {
        return find(modeId)
                .map(AgentOrchestrator::requiresWorkflowSteps)
                .orElse(false);
    }

    /**
     * 是否「显式配置了一个未注册的 mode」——即脏数据信号。
     * <p>
     * 与 {@link #isOrchestrated} 的 false 区分开：后者对 {@code "single"}（正常）与
     * {@code "teamm"}（拼错）返回同一个值，无法用于告警。本方法只在后一种情况返回 true。
     * <p>
     * 空值/空串不算脏数据 —— 存量场景 mode 列可能为空，那是正常的默认态。
     */
    public static boolean isUnregistered(String modeId) {
        return modeId != null && !modeId.isBlank() && find(modeId).isEmpty();
    }

    /**
     * 模式展示名，用于提示词与前端渲染；未注册时回退到单智能体的展示名。
     * <p>
     * 之所以让模式自己声明展示名，是因为「把内部枚举值翻译成人话」这件事
     * 属于模式自身的知识，不该由渲染方维护一张 switch 表。
     */
    public static String displayNameOf(String modeId) {
        return find(modeId)
                .map(AgentOrchestrator::displayName)
                .orElseGet(() -> find(DEFAULT_MODE)
                        .map(AgentOrchestrator::displayName)
                        .orElse("单智能体"));
    }

    /** 全部已注册的 modeId，按发现顺序 */
    public static java.util.List<String> modeIds() {
        return java.util.List.copyOf(MODES.keySet());
    }

    /** 全部已注册的模式 */
    public static java.util.Collection<AgentOrchestrator> all() {
        return MODES.values();
    }

    public static int size() {
        return MODES.size();
    }
}
