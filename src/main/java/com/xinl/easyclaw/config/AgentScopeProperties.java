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

        /**
         * 子 Agent 默认 ReAct 迭代次数（声明文件未写 steps 时生效）。
         * <p>
         * AgentScope 的 SubagentDeclaration.Builder 默认只有 10 步，子 Agent 一旦
         * 涉及多次文件读取 + 分析就会耗尽迭代，框架抛出 ExceedMaxItersEvent 并
         * 强行结束，表现为「子 Agent 回复被截断」。这里显式放宽到 30。
         */
        private int subagentSteps = 30;

        /** 单次模型调用超时（分钟）。复杂推理/长输出可能需要更久 */
        private int modelTimeoutMinutes = 10;

        /** 单次工具调用超时（分钟）。子 Agent 通过 agent_spawn 同步调用，必须大于子 Agent 任务耗时 */
        private int toolTimeoutMinutes = 30;

        /** 单条 shell 命令超时（秒）。编译、npm install 等长命令需要足够时间 */
        private int shellTimeoutSeconds = 300;

        /** shell 输出截断上限（字节）。防止工具结果撑爆上下文 */
        private int maxShellOutputBytes = 200_000;

        /** 上下文压缩触发：消息数阈值（压缩过狠会让 Agent 忘记任务目标，默认放宽到 120） */
        private int compactionTriggerMessages = 120;

        /** 上下文压缩触发：token 数阈值 */
        private long compactionTriggerTokens = 100_000;

        /** 压缩后保留的最近消息数（工具调用一轮至少占 2 条，20 条太少会丢任务上下文） */
        private int compactionKeepMessages = 40;

        /** 压缩后保留的 token 数 */
        private long compactionKeepTokens = 24_000;

        /** 压缩时预留给模型输出的 token 数 */
        private long compactionReservedTokens = 20_000;

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }

        public int getSubagentSteps() {
            return subagentSteps;
        }

        public void setSubagentSteps(int subagentSteps) {
            this.subagentSteps = subagentSteps;
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

        public int getCompactionTriggerMessages() {
            return compactionTriggerMessages;
        }

        public void setCompactionTriggerMessages(int compactionTriggerMessages) {
            this.compactionTriggerMessages = compactionTriggerMessages;
        }

        public long getCompactionTriggerTokens() {
            return compactionTriggerTokens;
        }

        public void setCompactionTriggerTokens(long compactionTriggerTokens) {
            this.compactionTriggerTokens = compactionTriggerTokens;
        }

        public int getCompactionKeepMessages() {
            return compactionKeepMessages;
        }

        public void setCompactionKeepMessages(int compactionKeepMessages) {
            this.compactionKeepMessages = compactionKeepMessages;
        }

        public long getCompactionKeepTokens() {
            return compactionKeepTokens;
        }

        public void setCompactionKeepTokens(long compactionKeepTokens) {
            this.compactionKeepTokens = compactionKeepTokens;
        }

        public long getCompactionReservedTokens() {
            return compactionReservedTokens;
        }

        public void setCompactionReservedTokens(long compactionReservedTokens) {
            this.compactionReservedTokens = compactionReservedTokens;
        }
    }
}
