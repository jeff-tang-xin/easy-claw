package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.agent.EasyClawAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 智能体注册表 —— 通过 {@link ServiceLoader} 发现全部 {@link EasyClawAgent} 实现。
 * <p>
 * <b>存在的意义</b>：让「新增一个智能体」退化为「新建一个模块 + 放一个
 * {@code META-INF/services} 文件」，公共代码零改动。在此之前，新增智能体需要同时改
 * {@code DataInitializer}（人格）、{@code subagents/*.md}（工具）、
 * {@code AiAssistantApplication.BUNDLED_SUBAGENTS}（注册）三处，靠同名字符串隐式关联，
 * 漏改任意一处都是静默失效。
 * <p>
 * <b>为什么用 ServiceLoader 而不是 Spring 扫描</b>：agent 模块只依赖 base（契约层），
 * 不依赖 Spring。这条边界是刻意的 —— 智能体是纯声明，不该因为换了容器就要重写。
 * 由本类在 Spring 侧做一次桥接，把 JDK SPI 的发现结果暴露成一个 Bean。
 * <p>
 * <b>加载时机</b>：构造期一次性加载并固化为不可变 Map。ServiceLoader 每次 {@code load}
 * 都会重新实例化，若每次查询都 load，同一个 agentId 会拿到不同实例，
 * 且实例化开销落在请求路径上。
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** agentId → 实现，保持 ServiceLoader 的发现顺序以便日志可读 */
    private final Map<String, EasyClawAgent> agents;

    public AgentRegistry() {
        this.agents = discover();
    }

    /**
     * 扫描 classpath 上全部 {@code EasyClawAgent} 实现。
     * <p>
     * <b>重复 agentId 的处理</b>：保留先注册的，跳过后来的并告警。静默覆盖会让
     * 「我明明改了代码怎么不生效」变成无从排查的问题 —— 两个 jar 提供同名智能体时，
     * 谁赢取决于 classpath 顺序，而 classpath 顺序在不同打包方式下并不稳定。
     * <p>
     * <b>单个实现加载失败不影响其余</b>：某个 agent 模块的类初始化异常
     * （如静态块报错、依赖缺失）只淘汰它自己，不能让整个应用起不来。
     */
    private static Map<String, EasyClawAgent> discover() {
        Map<String, EasyClawAgent> found = new LinkedHashMap<>();
        ServiceLoader<EasyClawAgent> loader = ServiceLoader.load(EasyClawAgent.class);
        for (EasyClawAgent agent : loader) {
            String id;
            try {
                id = agent.agentId();
            } catch (Exception e) {
                log.error("[SPI] 智能体 {} 的 agentId() 抛异常，已跳过",
                        agent.getClass().getName(), e);
                continue;
            }
            if (id == null || id.isBlank()) {
                log.error("[SPI] 智能体 {} 的 agentId() 返回空，已跳过",
                        agent.getClass().getName());
                continue;
            }
            EasyClawAgent prev = found.putIfAbsent(id, agent);
            if (prev != null) {
                log.warn("[SPI] agentId [{}] 重复：保留 {}，跳过 {}",
                        id, prev.getClass().getName(), agent.getClass().getName());
            }
        }
        log.info("[SPI] 已发现 {} 个智能体: {}", found.size(), found.keySet());
        return Map.copyOf(found);
    }

    /** 按 agentId 查找，找不到返回空 */
    public Optional<EasyClawAgent> find(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agents.get(agentId.trim()));
    }

    /** 是否存在该智能体 */
    public boolean contains(String agentId) {
        return find(agentId).isPresent();
    }

    /** 全部已注册的 agentId */
    public List<String> agentIds() {
        return List.copyOf(agents.keySet());
    }

    /** 全部已注册的智能体 */
    public Collection<EasyClawAgent> all() {
        return agents.values();
    }

    public int size() {
        return agents.size();
    }
}
