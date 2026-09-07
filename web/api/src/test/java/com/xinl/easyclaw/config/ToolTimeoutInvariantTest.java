package com.xinl.easyclaw.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 tool-timeout-minutes 的两个硬下界，防止配置再次漂移。
 *
 * <p>背景：本测试新增前，application.yml 把 tool-timeout-minutes 设为 10 分钟
 * = 600s，与 AgentSpawnTool.MAX_TIMEOUT_SECONDS(600) <b>恰好相等</b>。两个超时同时
 * 到点形成竞态：若外层工具超时先赢，agent_spawn 的「同步等待超时 → 提升为后台任务」
 * 降级路径就永远不会执行，多智能体编排在长任务下直接失败而非降级。
 *
 * <p>约束来源（均为读码确认，非推测）：
 * <ul>
 *   <li>AgentSpawnTool:110 {@code MAX_TIMEOUT_SECONDS = 600}，
 *       :1504 {@code Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1000} 钳制同步等待；
 *       agent_spawn 自身是一次工具调用，因此受 toolTimeoutMinutes 管辖。</li>
 *   <li>shellTimeoutSeconds 默认 300s，execute 工具可合法跑满；外层若先超时，
 *       shell 自身的超时配置永远不生效。</li>
 * </ul>
 *
 * <p>注意：这里断言的是不等式而非具体数值 —— 调整超时是正常运维行为，
 * 但突破下界是 bug。直接用 Binder 绑定<b>真实</b> application.yml，
 * 而不是 new AgentScopeProperties()，否则测不到 yml 的漂移；也不用 @SpringBootTest，
 * 那会拉起 WebSocket 容器（无 servlet 环境时报 ServerContainer not found），
 * 对一个纯配置不变量来说过重。
 */
@DisplayName("tool-timeout-minutes 下界不变量")
class ToolTimeoutInvariantTest {

    /** 与 AgentSpawnTool.MAX_TIMEOUT_SECONDS 保持一致。 */
    private static final int SPAWN_MAX_SYNC_WAIT_SECONDS = 600;

    private static AgentScopeProperties props;

    @BeforeAll
    static void bindRealYaml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        // yml 是多文档（--- 分隔），全部挂载；靠前的文档优先级更高
        sources.forEach(s -> env.getPropertySources().addLast(s));
        props = Binder.get(env)
                .bind("agentscope", AgentScopeProperties.class)
                .orElseThrow(() -> new IllegalStateException(
                        "未能从 application.yml 绑定 agentscope 配置"));
    }

    @Test
    @DisplayName("必须严格大于 agent_spawn 的 600s 同步等待上限，否则异步降级路径失效")
    void toolTimeoutMustExceedSpawnSyncCap() {
        int toolTimeoutSeconds = props.getAgent().getToolTimeoutMinutes() * 60;
        assertTrue(
                toolTimeoutSeconds > SPAWN_MAX_SYNC_WAIT_SECONDS,
                () -> "tool-timeout-minutes 换算为 " + toolTimeoutSeconds + "s，未超过 agent_spawn "
                        + "同步等待上限 " + SPAWN_MAX_SYNC_WAIT_SECONDS + "s。"
                        + "相等或更小都会让 spawn 在「提升为后台任务」之前被外层掐断。");
    }

    @Test
    @DisplayName("必须严格大于 shell-timeout-seconds，否则 shell 自身超时永不生效")
    void toolTimeoutMustExceedShellTimeout() {
        int toolTimeoutSeconds = props.getAgent().getToolTimeoutMinutes() * 60;
        int shellTimeoutSeconds = props.getAgent().getShellTimeoutSeconds();
        assertTrue(
                toolTimeoutSeconds > shellTimeoutSeconds,
                () -> "tool-timeout-minutes 换算为 " + toolTimeoutSeconds
                        + "s，未超过 shell-timeout-seconds " + shellTimeoutSeconds + "s。");
    }
}
