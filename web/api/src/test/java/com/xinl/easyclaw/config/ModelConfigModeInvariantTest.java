package com.xinl.easyclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 local / cloud 两种模式下的模型配置不变量，防止配置再次漂移。
 *
 * <p>背景（本测试新增前踩过的坑，均为读码确认）：
 * <ul>
 *   <li><b>provider 笔误静默降级</b>：{@code application.yml} 里曾留着示例段
 *       {@code agents.coder.provider: openapi}，而 providers 表中并无 {@code openapi} 这个 key。
 *       {@code ModelRegistryService.resolveAgentModel} 里 {@code providers.get("openapi")} 返回 null
 *       → {@code cfg} 为 null → 模型名回退到 {@code AgentScopeProperties.Model.modelName} 的 Java 字段
 *       默认值 {@code gpt-4o-mini}。cloud 下等于绕过 {@code hub_cloud} 逻辑别名、把真实模型名直发 hub
 *       网关（被 appkey 绑定面校验拒 404）；local 下等于向当前 provider 请求一个不存在的模型。
 *       两种模式该子 Agent 都不可用，且日志看不出是配置笔误。
 *       —— 校验只写在注释里挡不住这种笔误，故固化为断言。</li>
 *   <li><b>cloud 不该维护输出上限</b>：cloud 只发 {@code hub_cloud}，真实 provider+model 由 hub 按
 *       appkey 路由决定，web 不感知真实模型，公共 {@code model-limits} 表在云端无一条该生效；
 *       统一下发 {@code max-tokens} 可能超过被路由到的低上限模型并触发 400。
 *       该开关必须是代码级短路，不能靠「模型名恰好匹配不到」，也不能靠 profile 清空 map
 *       （Spring 多文档 yaml 对 map 是逐 key 合并，清空不可靠）。</li>
 * </ul>
 *
 * <p>手法沿用 {@link ToolTimeoutInvariantTest}：用 {@code Binder} 绑定<b>真实</b> yml 文件，
 * 而不是 {@code new AgentScopeProperties()}，否则测不到 yml 漂移；也不用 {@code @SpringBootTest}，
 * 那会拉起 WebSocket 容器，对纯配置不变量过重。
 */
@DisplayName("local/cloud 模型配置模式不变量")
class ModelConfigModeInvariantTest {

    /**
     * 按 Spring 的 profile 语义合并 yml：profile 文件覆盖公共文件，同一文件内先出现的文档优先。
     *
     * <p>顺序不能弄错：{@code StandardEnvironment} 的属性源是按加入先后查找，先加入者优先。
     * 若一律 {@code addLast}，公共 {@code application.yml} 会盖住 profile 文件，
     * {@code max-tokens: null}、{@code model-limits-enabled: false} 这类覆盖都测不出来，
     * 断言会指向与运行时相反的配置。
     */
    private static AgentScopeProperties bind(String... resources) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        for (int i = 0; i < resources.length; i++) {
            mount(env, resources[i], i > 0);
        }
        return Binder.get(env)
                .bind("agentscope", AgentScopeProperties.class)
                .orElseThrow(() -> new IllegalStateException("未能从 yml 绑定 agentscope 配置"));
    }

    private static void mount(StandardEnvironment env, String resource, boolean overridesPrevious)
            throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        if (overridesPrevious) {
            // 逆序 addFirst：让本文件整体压到最前，同时保持文件内先出现的文档优先级更高
            for (int i = sources.size() - 1; i >= 0; i--) {
                env.getPropertySources().addFirst(sources.get(i));
            }
        } else {
            sources.forEach(s -> env.getPropertySources().addLast(s));
        }
    }

    @Test
    @DisplayName("agents.<id>.provider 必须是 providers 表中存在的 key")
    void agentProviderRefsMustResolve() throws Exception {
        assertProvidersResolve(bind("application.yml", "application-local.yml"), "local");
        assertProvidersResolve(bind("application.yml", "application-cloud.yml"), "cloud");
    }

    private static void assertProvidersResolve(AgentScopeProperties props, String profile) {
        for (Map.Entry<String, AgentScopeProperties.AgentModelConfig> entry : props.getAgents().entrySet()) {
            String provider = entry.getValue().getProvider();
            if (provider == null || provider.isBlank()) {
                continue;
            }
            assertTrue(props.getProviders().containsKey(provider),
                    "profile=" + profile + " 下 agents." + entry.getKey() + ".provider=" + provider
                            + " 在 providers 表中不存在：providerCfg 会解析为 null，模型名静默回退到 Java 字段默认值"
                            + " gpt-4o-mini，cloud 下等于绕过 hub_cloud 别名直发真实模型名给 hub 网关。");
        }
    }

    @Test
    @DisplayName("cloud 关闭输出上限维护，local 保留")
    void onlyLocalMaintainsModelLimits() throws Exception {
        AgentScopeProperties local = bind("application.yml", "application-local.yml");
        AgentScopeProperties cloud = bind("application.yml", "application-cloud.yml");

        assertTrue(local.isModelLimitsEnabled(),
                "local 必须保留 model-limits：按真实模型名下发上限是多模型共用一套密钥的前提");
        assertFalse(cloud.isModelLimitsEnabled(),
                "cloud 必须关闭上限维护：真实模型由 hub 按 appkey 路由决定，web 不感知");
    }

    /**
     * cloud 继承得到<b>非空</b>的 model-limits 表，因此只能靠 {@code model-limits-enabled}
     * 代码级短路，不能指望「表里没有 hub_cloud 这一条」。
     *
     * <p>两个 Spring 语义坑（均为本测试实测确认）：
     * <ul>
     *   <li>profile 文件无法整体清空 map：多份 yml 对 map 是逐 key 合并，公共表照样继承。</li>
     *   <li>yml 写 {@code max-tokens: null} 不等于把值置空。PropertySource 里 null 视作属性缺席，
     *       Binder 回落到 {@code AgentScopeProperties.Model.maxTokens} 的 Java 默认值 32768。
     *       该值在 cloud 下不会下发，仅仅是因为 {@code resolveMaxTokens} 在三级回退<b>之前</b>
     *       就被开关短路——开关一撤，32768 立刻重新生效。故此处不断言 maxTokens 为 null。</li>
     * </ul>
     */
    @Test
    @DisplayName("cloud 继承到非空 model-limits 表，证明必须用代码开关短路")
    void cloudInheritsModelLimitsSoSwitchIsTheOnlyGuard() throws Exception {
        AgentScopeProperties cloud = bind("application.yml", "application-cloud.yml");

        assertFalse(cloud.getModelLimits().isEmpty(),
                "预期 cloud 会继承公共 model-limits 表：若此断言失败，说明 profile 清空 map 变得可靠了，"
                        + "可重新评估 model-limits-enabled 开关是否还有必要");
        assertFalse(cloud.isModelLimitsEnabled(),
                "继承到非空表却不下发，全靠这个开关；它是 cloud 模式下唯一的防线");
    }

    @Test
    @DisplayName("cloud 激活的 provider 必须指向 hub_cloud 逻辑别名")
    void cloudProviderMustServeHubCloudAlias() throws Exception {
        AgentScopeProperties cloud = bind("application.yml", "application-cloud.yml");
        String activeProvider = cloud.getModel().getProvider();

        AgentScopeProperties.ProviderConfig cfg = cloud.getProviders().get(activeProvider);
        assertNotNull(cfg, "cloud 激活 provider=" + activeProvider + " 必须在 providers 表中存在");
        assertEquals("hub_cloud", cfg.getModelName(),
                "cloud 必须发逻辑别名 hub_cloud，由 hub 按 appkey 路由到真实 provider+model");
    }
}
