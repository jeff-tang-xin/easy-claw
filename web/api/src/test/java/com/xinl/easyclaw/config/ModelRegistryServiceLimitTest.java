package com.xinl.easyclaw.config;

import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 route / 网关型 provider 下「按模型名的 max output tokens 三级回退」。
 * <p>
 * 背景：同一 base-url 后面挂 kimi / 豆包 / deepseek / glm 等模型，
 * 各家输出上限差异极大，统一下发一个值会让上限低的模型报 400，
 * 而值太小又会截断 write_file 的 tool_call arguments 导致参数丢失。
 */
class ModelRegistryServiceLimitTest {

    private AgentScopeProperties props;
    private ModelRegistryService service;

    @BeforeEach
    void setUp() {
        props = new AgentScopeProperties();
        props.getModel().setMaxTokens(8192);
        props.getModel().setParallelToolCalls(true);
        putLimit("glm-4-flash", 4095, null);
        putLimit("glm-*", 8192, null);
        putLimit("glm-4.5*", 32768, null);
        putLimit("kimi-k2*", 32768, null);
        putLimit("kimi-*", 16384, null);
        putLimit("deepseek:deepseek-reasoner", 32768, null);
        putLimit("deepseek-reasoner", 4096, null);
        putLimit("o1-preview", 1234, true);
        service = new ModelRegistryService(props);
    }

    private void putLimit(String key, Integer maxTokens, Boolean unsupported) {
        AgentScopeProperties.ModelLimit limit = new AgentScopeProperties.ModelLimit();
        limit.setMaxTokens(maxTokens);
        limit.setMaxTokensUnsupported(unsupported);
        props.getModelLimits().put(key, limit);
    }

    @Test
    @DisplayName("模型名精确匹配优先于通配前缀")
    void exactModelNameWins() {
        assertEquals(4095, service.resolveMaxTokens("zhipu", null, "glm-4-flash"));
    }

    @Test
    @DisplayName("多个通配前缀命中时取最长前缀")
    void longestPrefixWins() {
        assertEquals(32768, service.resolveMaxTokens("zhipu", null, "glm-4.5-air"));
        assertEquals(8192, service.resolveMaxTokens("zhipu", null, "glm-4-air"));
        assertEquals(32768, service.resolveMaxTokens("moonshot", null, "kimi-k2-0905"));
        assertEquals(16384, service.resolveMaxTokens("moonshot", null, "kimi-latest"));
    }

    @Test
    @DisplayName("provider:model 限定键优先于裸模型名")
    void qualifiedKeyWins() {
        assertEquals(32768, service.resolveMaxTokens("deepseek", null, "deepseek-reasoner"));
        assertEquals(4096, service.resolveMaxTokens("route", null, "deepseek-reasoner"));
    }

    @Test
    @DisplayName("未命中模型表时回退 provider 级，再回退全局")
    void fallsBackToProviderThenGlobal() {
        AgentScopeProperties.ProviderConfig cfg = new AgentScopeProperties.ProviderConfig();
        cfg.setMaxTokens(2048);
        assertEquals(2048, service.resolveMaxTokens("route", cfg, "doubao-pro-32k"));
        assertEquals(8192, service.resolveMaxTokens("route", null, "doubao-pro-32k"));
    }

    @Test
    @DisplayName("声明不支持 max_tokens 的模型不下发该参数")
    void unsupportedModelReturnsNull() {
        assertNull(service.resolveMaxTokens("openai", null, "o1-preview"));
    }

    @Test
    @DisplayName("模型名为空或表为空时安全回退全局值")
    void blankModelNameFallsBack() {
        assertEquals(8192, service.resolveMaxTokens("route", null, null));
        assertEquals(8192, service.resolveMaxTokens("route", null, "  "));
        props.getModelLimits().clear();
        assertEquals(8192, service.resolveMaxTokens("zhipu", null, "glm-4-flash"));
    }

    @Test
    @DisplayName("模型名大小写不敏感")
    void matchingIsCaseInsensitive() {
        assertEquals(4095, service.resolveMaxTokens("ZhiPu", null, "GLM-4-Flash"));
    }

    @Test
    @DisplayName("cloud 的 hub_cloud 逻辑别名不传 max tokens，但保留并行工具调用开关")
    void hubCloudGenerateOptionsOmitsMaxTokens() {
        props.getModel().setMaxTokens(null);
        AgentScopeProperties.ProviderConfig cfg = new AgentScopeProperties.ProviderConfig();
        cfg.setParallelToolCalls(false);

        GenerateOptions options = service.buildGenerateOptions("openai", cfg, "hub_cloud");

        assertNull(options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
        assertFalse(options.getParallelToolCalls());
    }

    @Test
    @DisplayName("默认只下发 max_tokens，不同时带 max_completion_tokens")
    void explicitLimitWritesMaxTokensOnly() {
        GenerateOptions options = service.buildGenerateOptions("zhipu", null, "glm-4-flash");

        assertEquals(4095, options.getMaxTokens());
        // 两字段在协议上互斥，同时下发会被严格校验的上游（如火山方舟）判为非法参数组合 400
        assertNull(options.getMaxCompletionTokens());
        assertTrue(options.getParallelToolCalls());
    }

    @Test
    @DisplayName("声明 max-completion-tokens-only 的模型改用新字段，且不再带 max_tokens")
    void completionTokensOnlyModelSwitchesField() {
        AgentScopeProperties.ModelLimit limit = new AgentScopeProperties.ModelLimit();
        limit.setMaxTokens(16384);
        limit.setMaxCompletionTokensOnly(true);
        props.getModelLimits().put("o4-mini", limit);

        GenerateOptions options = service.buildGenerateOptions("openai", null, "o4-mini");

        assertEquals(16384, options.getMaxCompletionTokens());
        assertNull(options.getMaxTokens());
    }

    @Test
    @DisplayName("max-tokens-unsupported 优先，两个字段都不下发")
    void unsupportedWinsOverFieldSwitch() {
        AgentScopeProperties.ModelLimit limit = new AgentScopeProperties.ModelLimit();
        limit.setMaxTokens(16384);
        limit.setMaxTokensUnsupported(true);
        limit.setMaxCompletionTokensOnly(true);
        props.getModelLimits().put("strict-reasoner", limit);

        GenerateOptions options = service.buildGenerateOptions("openai", null, "strict-reasoner");

        assertNull(options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
    }

    @Test
    @DisplayName("关闭 model-limits 后任何模型都不下发输出上限（cloud 语义）")
    void modelLimitsDisabledOmitsAllCaps() {
        props.setModelLimitsEnabled(false);

        // 命中模型表、未命中、provider 级有值三种路径，关闭后一律不下发
        GenerateOptions hit = service.buildGenerateOptions("zhipu", null, "glm-4-flash");
        GenerateOptions alias = service.buildGenerateOptions("openai", null, "hub_cloud");
        AgentScopeProperties.ProviderConfig cfg = new AgentScopeProperties.ProviderConfig();
        cfg.setMaxTokens(2048);
        GenerateOptions providerLevel = service.buildGenerateOptions("route", cfg, "doubao-pro-32k");

        assertNull(hit.getMaxTokens());
        assertNull(hit.getMaxCompletionTokens());
        assertNull(alias.getMaxTokens());
        assertNull(providerLevel.getMaxTokens());
        // 上限关闭不影响并行工具调用开关按配置生效
        assertTrue(hit.getParallelToolCalls());
    }

    @Test
    @DisplayName("默认开启 model-limits（local 语义），按模型名下发上限")
    void modelLimitsEnabledByDefault() {
        assertTrue(props.isModelLimitsEnabled());
        assertEquals(4095, service.resolveMaxTokens("zhipu", null, "glm-4-flash"));
    }
}
