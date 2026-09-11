package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot 集成测试 —— 验证三个关键点：
 * <ol>
 *   <li>{@code @PostConstruct} fail-fast 不会误触发（SPI 能在真实 classpath 找到所有模式）</li>
 *   <li>Bean 门面与静态入口看到的同一份数据</li>
 *   <li>single 模式的数据流（编排判定、步数判定）不受影响</li>
 * </ol>
 * <p>
 * 注意：本测试需要完整的 Spring 上下文，耗时较长，不纳入常规 TDD 循环。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpiIntegrationTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private OrchestratorRegistry registry;

    @Test
    @DisplayName("应用启动成功：@PostConstruct 未抛异常，OrchestratorRegistry 已注入")
    void contextLoads() {
        assertNotNull(ctx);
        assertNotNull(registry);
    }

    @Test
    @DisplayName("SPI 发现 3 种编排模式，含单智能体必备模式 single")
    void spiDiscoversThreeModes() {
        assertEquals(3, registry.size(), "实际发现: " + registry.modeIds());
        assertTrue(registry.find("single").isPresent(),
                "缺少 single 模式，应用启动就会在 @PostConstruct 阶段抛异常");
        assertTrue(registry.find("team").isPresent());
        assertTrue(registry.find("schedule").isPresent());
    }

    @Test
    @DisplayName("Bean 门面与静态入口数据一致，不存在两份独立发现")
    void beanAndStaticEntryAgree() {
        assertEquals(registry.modeIds(), OrchestrationModes.modeIds());
        assertEquals(registry.size(), OrchestrationModes.size());
    }

    @Test
    @DisplayName("single 场景的编排判定为 false，子 Agent 步数不受影响")
    void singleScenarioIsNotOrchestrated() {
        assertFalse(registry.isOrchestrated(scenarioWithMode("single")));
        assertFalse(ScenarioBinding.from(scenarioWithMode("single")).isOrchestratedMode());
        assertFalse(ScenarioBinding.EMPTY.isOrchestratedMode());
    }

    @Test
    @DisplayName("single 场景 resolve 返回 single 模式")
    void singleScenarioResolvesToSingle() {
        assertEquals("single", registry.resolve(scenarioWithMode("single")).modeId());
        assertEquals("single", registry.resolve(null).modeId());
        assertEquals("single", registry.resolve(scenarioWithMode(null)).modeId());
    }

    private static ScenarioEntity scenarioWithMode(String mode) {
        ScenarioEntity e = new ScenarioEntity();
        e.setMode(mode);
        return e;
    }
}