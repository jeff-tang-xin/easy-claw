package com.xinl.easyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentScope 配置属性
 * <p>
 * 映射 application.yml 中 agentscope.* 配置。
 * 支持多模型 Provider（OpenAI / DeepSeek / DashScope / Ollama / 其他 OpenAI 兼容端点），
 * 每个 Provider 可独立配置 api-key / base-url / model-name，UI 设置页可实时切换。
 */
@ConfigurationProperties(prefix = "agentscope")
public class AgentScopeProperties {

    private Model model = new Model();

    /** 多 Provider 配置表：key 为 provider 名（如 deepseek、openai） */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    /**
     * 当前激活的 Provider 配置（按 model.provider 查找，找不到时返回默认）
     */
    public ProviderConfig getActiveProviderConfig() {
        ProviderConfig cfg = providers.get(model.getProvider());
        if (cfg == null) {
            cfg = new ProviderConfig();
        }
        return cfg;
    }

    public static class Model {
        private String provider = "openai";
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String modelName = "gpt-4o-mini";
        private String defaultModel = "gpt-4o-mini";
        private Double temperature = 0.7;
        private Boolean stream = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Boolean getStream() {
            return stream;
        }

        public void setStream(Boolean stream) {
            this.stream = stream;
        }
    }

    /**
     * 单个模型 Provider 配置
     */
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private Double temperature;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
    }
}
