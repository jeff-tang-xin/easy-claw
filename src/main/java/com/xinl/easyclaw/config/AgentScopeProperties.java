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

    /** Agent 运行时配置（迭代、超时等） */
    private Agent agent = new Agent();

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

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    /**
     * Agent 运行时配置
     */
    public static class Agent {
        /** ReAct 最大迭代次数（默认 50，15 太少会导致长任务中途被强制结束） */
        private int maxIters = 50;

        /** 单次模型调用超时（分钟）。复杂推理/长输出可能需要更久 */
        private int modelTimeoutMinutes = 10;

        /** 单次工具调用超时（分钟）。子 Agent 通过 agent_spawn 同步调用，必须大于子 Agent 任务耗时 */
        private int toolTimeoutMinutes = 30;

        /** 单条 shell 命令超时（秒）。编译、npm install 等长命令需要足够时间 */
        private int shellTimeoutSeconds = 300;

        /** shell 输出截断上限（字节）。防止工具结果撑爆上下文 */
        private int maxShellOutputBytes = 200_000;

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }

        public int getModelTimeoutMinutes() {
            return modelTimeoutMinutes;
        }

        public void setModelTimeoutMinutes(int modelTimeoutMinutes) {
            this.modelTimeoutMinutes = modelTimeoutMinutes;
        }

        public int getToolTimeoutMinutes() {
            return toolTimeoutMinutes;
        }

        public void setToolTimeoutMinutes(int toolTimeoutMinutes) {
            this.toolTimeoutMinutes = toolTimeoutMinutes;
        }

        public int getShellTimeoutSeconds() {
            return shellTimeoutSeconds;
        }

        public void setShellTimeoutSeconds(int shellTimeoutSeconds) {
            this.shellTimeoutSeconds = shellTimeoutSeconds;
        }

        public int getMaxShellOutputBytes() {
            return maxShellOutputBytes;
        }

        public void setMaxShellOutputBytes(int maxShellOutputBytes) {
            this.maxShellOutputBytes = maxShellOutputBytes;
        }
    }
}
