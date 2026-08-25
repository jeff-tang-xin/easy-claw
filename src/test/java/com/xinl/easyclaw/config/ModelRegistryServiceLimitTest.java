package com.xinl.easyclaw.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
