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

    /**
     * 按「模型名」粒度的生成参数上限表：key 为模型名或前缀通配（如 {@code glm-4-flash}、{@code kimi-*}）。
     * <p>
     * 适用于 route / 网关型 provider：同一个 base-url + api-key 后面挂着 kimi、豆包、
     * deepseek、glm 等多个模型，而各家的单次输出 token 上限差异极大
     * （glm-4-flash 仅 4095，deepseek-reasoner 可达 32K+）。若统一按最大值下发，
     * 上限低的模型会被服务端直接拒绝（HTTP 400 max_tokens 超限）；
     * 若统一按最小值下发，大文件写入的 tool_call arguments 又会被截断。
     * <p>
     * 生效优先级：模型名精确匹配 &gt; 模型名前缀通配（最长优先）&gt; provider 级配置 &gt; 全局 model 配置。
     */
    private Map<String, ModelLimit> modelLimits = new LinkedHashMap<>();

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

    public Map<String, ModelLimit> getModelLimits() {
        return modelLimits;
    }

    public void setModelLimits(Map<String, ModelLimit> modelLimits) {
        this.modelLimits = modelLimits;
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

        /**
         * 单次响应最大输出 token 数。
         * <p>
         * 必须显式配置：不配置时服务端默认上限（常见 4096）会把长 tool_call arguments
         * 的 JSON 直接截断，累积后不是合法 JSON 对象，被上游
         * {@code ToolCallBuilder.build()} 静默降级为 {@code {}}，
         * 表现为「未找到 path/content/old_string 参数」。
         */
        private Integer maxTokens = 32768;

        /**
         * 是否允许模型在一轮内并行发起多个 tool_call。
         * <p>
         * 置为 false：上游流式分片累积器用「最后一个 tool_call key」兜底路由无名分片
         * （{@code ToolCallsAccumulator.determineKey} → {@code lastToolCallKey}），
         * 多个 tool_call 的 arguments 分片交错到达时会串台，导致 JSON 破损、参数丢失。
         */
        private Boolean parallelToolCalls = false;

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

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getParallelToolCalls() {
            return parallelToolCalls;
        }

        public void setParallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
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

        /** 该 Provider 的单次最大输出 token（null 表示继承全局 model.max-tokens） */
        private Integer maxTokens;

        /** 该 Provider 是否允许并行 tool_call（null 表示继承全局 model.parallel-tool-calls） */
        private Boolean parallelToolCalls;

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

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getParallelToolCalls() {
            return parallelToolCalls;
        }

        public void setParallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
        }
    }

    /**
     * 单个模型（或模型名通配前缀）的生成参数上限。
     * <p>
     * 只覆盖需要区别对待的项，未设置的字段继承 provider 级 / 全局配置。
     */
    public static class ModelLimit {
        /** 单次最大输出 token */
        private Integer maxTokens;

        /** 是否允许并行 tool_call */
        private Boolean parallelToolCalls;

        /** 该模型是否完全不支持 max_tokens 参数（部分推理模型会拒绝该字段），true 时不下发 */
        private Boolean maxTokensUnsupported;

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Boolean getParallelToolCalls() {
            return parallelToolCalls;
        }

        public void setParallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
        }

        public Boolean getMaxTokensUnsupported() {
            return maxTokensUnsupported;
        }

        public void setMaxTokensUnsupported(Boolean maxTokensUnsupported) {
            this.maxTokensUnsupported = maxTokensUnsupported;
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

        /**
         * 单次工具调用超时（分钟）。
         *
         * <p>下界由两个硬约束决定，调低前务必确认：
         * <ul>
         *   <li>agent_spawn 同步等待被框架钳制在 600s（AgentSpawnTool.MAX_TIMEOUT_SECONDS），
         *       到点自行「提升为后台任务」返回；本超时若小于 600s，会在提升前就掐断 spawn，
         *       多智能体编排的异步降级路径直接失效。</li>
         *   <li>shellTimeoutSeconds 默认 300s，execute 工具可合法跑满。</li>
         * </ul>
         *
         * <p>取 12 分钟 = 600s 上界 + 120s 余量。原值 30 分钟并无依据，且工具链目前
         * 缺少可中断点（见 AgentService#recoverStuckAgent），卡死工具会占住 boundedElastic
         * 线程直到超时；在并发子 Agent 场景下该占用按并发度放大，故收紧至满足约束的最小值。
         */
        private int toolTimeoutMinutes = 12;

        /**
         * 工具确认等待超时（分钟）。用户迟迟不点确认/拒绝（关页面、切走、误触）时，
         * 超过该时长自动取消本轮，释放 SSE 连接与会话内存状态。0 或负数表示不超时。
         */
        private int confirmTimeoutMinutes = 10;

        /**
         * SSE 连接超时（分钟）。0 表示永不超时（不推荐：存在「流已终止但连接不关」的路径，
         * 会让异步请求与会话状态常驻）。应大于单回合最长耗时 + confirmTimeoutMinutes。
         */
        private int sseTimeoutMinutes = 60;

        /** 单次请求附件数量上限，入口即拒，避免超量 base64 进入内存 */
        private int maxAttachments = 20;

        /** 单次请求附件 base64 总长度上限（字节），默认 32MB */
        private long maxAttachmentBytes = 32L * 1024 * 1024;

        /**
         * 同时活跃的 SSE 连接数上限。每条连接背后挂着一个 Agent 回合与一份会话内存，
         * 不限流则单个客户端反复调 /stream 即可耗尽容器工作线程。0 或负数表示不限制。
         */
        private int maxSseConnections = 64;

        /**
         * 同一会话内同一子 Agent 的最大调度次数。超过即视为循环调度并 interrupt。
         * 这是策略参数而非实现细节，故可配置（原先硬编码在事件分发逻辑中）。
         */
        private int maxSameSubagentCalls = 3;

        /** 同一工具连续失败次数阈值，达到即向模型注入「停止重试」提示 */
        private int maxConsecutiveToolFailures = 2;

        /**
         * Workspace Agent 缓存上限。每个 WorkspaceContext 持有 HarnessAgent
         * （模型客户端 + 工具集 + MCP 连接），无上限则句柄与内存随工作区数线性增长。
         * 超限时按 lastAccessed 最旧优先驱逐并 close。0 或负数表示不限制。
         */
        private int maxCachedWorkspaces = 32;

        /** Workspace Agent 缓存空闲驱逐时长（分钟）：超过该时长未访问即关闭释放资源 */
        private int workspaceIdleMinutes = 120;

        /** 单条 shell 命令超时（秒）。编译、npm install 等长命令需要足够时间 */
        private int shellTimeoutSeconds = 300;

        /**
         * shell 命令超时上限（秒）：LLM 显式传入的 timeout 会被夹到 [1, 该值] 区间。
         * <p>防止模型传入一个超大 timeout（如 3600）把工具线程占住远超预期。
         */
        private int shellMaxTimeoutSeconds = 600;

        /**
         * 硬截止宽限期（秒）：命令超时后，留给「杀进程树 + 收集已产出输出」的额外时间。
         * <p>宽限期一到即使清理未完成也立刻返回，保证 execute 的墙钟耗时有确定上界，
         * 绝不把调用线程无限期挂住（fast-fail 的最后一道保险）。
         */
        private int shellKillGraceSeconds = 5;

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

        public int getConfirmTimeoutMinutes() {
            return confirmTimeoutMinutes;
        }

        public void setConfirmTimeoutMinutes(int confirmTimeoutMinutes) {
            this.confirmTimeoutMinutes = confirmTimeoutMinutes;
        }

        public int getSseTimeoutMinutes() {
            return sseTimeoutMinutes;
        }

        public void setSseTimeoutMinutes(int sseTimeoutMinutes) {
            this.sseTimeoutMinutes = sseTimeoutMinutes;
        }

        public int getMaxAttachments() {
            return maxAttachments;
        }

        public void setMaxAttachments(int maxAttachments) {
            this.maxAttachments = maxAttachments;
        }

        public long getMaxAttachmentBytes() {
            return maxAttachmentBytes;
        }

        public void setMaxAttachmentBytes(long maxAttachmentBytes) {
            this.maxAttachmentBytes = maxAttachmentBytes;
        }

        public int getMaxSameSubagentCalls() {
            return maxSameSubagentCalls;
        }

        public void setMaxSameSubagentCalls(int maxSameSubagentCalls) {
            this.maxSameSubagentCalls = maxSameSubagentCalls;
        }

        public int getMaxConsecutiveToolFailures() {
            return maxConsecutiveToolFailures;
        }

        public void setMaxConsecutiveToolFailures(int maxConsecutiveToolFailures) {
            this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
        }

        public int getMaxCachedWorkspaces() {
            return maxCachedWorkspaces;
        }

        public void setMaxCachedWorkspaces(int maxCachedWorkspaces) {
            this.maxCachedWorkspaces = maxCachedWorkspaces;
        }

        public int getWorkspaceIdleMinutes() {
            return workspaceIdleMinutes;
        }

        public void setWorkspaceIdleMinutes(int workspaceIdleMinutes) {
            this.workspaceIdleMinutes = workspaceIdleMinutes;
        }

        public int getMaxSseConnections() {
            return maxSseConnections;
        }

        public void setMaxSseConnections(int maxSseConnections) {
            this.maxSseConnections = maxSseConnections;
        }

        public int getShellTimeoutSeconds() {
            return shellTimeoutSeconds;
        }

        public void setShellTimeoutSeconds(int shellTimeoutSeconds) {
            this.shellTimeoutSeconds = shellTimeoutSeconds;
        }

        public int getShellMaxTimeoutSeconds() {
            return shellMaxTimeoutSeconds;
        }

        public void setShellMaxTimeoutSeconds(int shellMaxTimeoutSeconds) {
            this.shellMaxTimeoutSeconds = shellMaxTimeoutSeconds;
        }

        public int getShellKillGraceSeconds() {
            return shellKillGraceSeconds;
        }

        public void setShellKillGraceSeconds(int shellKillGraceSeconds) {
            this.shellKillGraceSeconds = shellKillGraceSeconds;
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
