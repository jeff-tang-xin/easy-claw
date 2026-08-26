package com.xinl.easyclaw.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * 模型注册中心服务
 * <p>
 * 将 application.yml（或设置页）中配置的多个模型 Provider 注册到 AgentScope 的
 * {@link ModelRegistry}，使 Agent 可以通过字符串模型 ID（如 "deepseek:deepseek-chat"）解析模型。
 * <p>
 * 说明：DeepSeek / DashScope / Ollama 等均兼容 OpenAI Chat Completions 协议，
 * 因此统一使用 {@link OpenAIChatModel} 构建，无需额外扩展依赖。
 */
@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    private final AgentScopeProperties props;

    public ModelRegistryService(AgentScopeProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 重新加载所有已配置的 Provider 到 ModelRegistry（设置页修改后调用）
     */
    public synchronized void reload() {
        int registered = 0;
        for (var entry : props.getProviders().entrySet()) {
            String providerName = entry.getKey().trim().toLowerCase(Locale.ROOT);
            AgentScopeProperties.ProviderConfig cfg = entry.getValue();
            if (cfg == null || cfg.getModelName() == null || cfg.getModelName().isBlank()) {
                continue;
            }
            try {
                String modelId = providerName + ":" + cfg.getModelName().trim();
                ModelRegistry.register(modelId, buildModel(providerName, cfg));
                log.info("已注册模型: {} -> {}", modelId, cfg.getBaseUrl());
                registered++;
            } catch (Exception e) {
                log.warn("注册模型 {} 失败: {}", providerName, e.getMessage());
            }
        }

        // 兜底：确保激活的 provider 也可解析
        AgentScopeProperties.ProviderConfig active = props.getActiveProviderConfig();
        String activeProvider = props.getModel().getProvider().trim().toLowerCase(Locale.ROOT);
        if (active.getModelName() != null && !active.getModelName().isBlank()) {
            String modelId = activeProvider + ":" + active.getModelName().trim();
            try {
                ModelRegistry.register(modelId, buildModel(activeProvider, active));
                registered++;
            } catch (Exception e) {
                log.warn("注册激活模型 {} 失败: {}", modelId, e.getMessage());
            }
        }
        log.info("ModelRegistry 加载完成，共注册 {} 个模型", registered);
    }

    /**
     * 解析模型 ID：先查注册表，未命中则用当前激活的 OpenAI 兼容协议动态构建并缓存。
     * <p>
     * 所有 Provider 都走 OpenAI Chat Completions 协议（OpenAIChatModel），
     * 所以角色/子 Agent 只需填 model name 即可，不用关心 provider 是什么：
     * <ul>
     *   <li>{@code "kimi-k2.7-code"} → 用激活 provider 的 baseUrl + apiKey + 此 modelName</li>
     *   <li>{@code "deepseek:deepseek-reasoner"} → 从 providers 表取对应 provider 配置</li>
     *   <li>空值 → 回退全局默认模型</li>
     * </ul>
     * 动态构建的模型会注册到 ModelRegistry，下次同 modelId 直接命中。
     */
    public io.agentscope.core.model.Model resolveOrBuild(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return ModelRegistry.resolve(resolveModelId());
        }
        String trimmed = modelId.trim();
        if (ModelRegistry.canResolve(trimmed)) {
            return ModelRegistry.resolve(trimmed);
        }

        String providerName;
        String modelName;
        int idx = trimmed.indexOf(':');
        if (idx > 0) {
            providerName = trimmed.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            modelName = trimmed.substring(idx + 1).trim();
        } else {
            // 裸 model name → 用当前激活的协议 provider（默认 openai）
            providerName = props.getModel().getProvider().trim().toLowerCase(Locale.ROOT);
            if (providerName == null || providerName.isBlank()) {
                providerName = "openai";
            }
            modelName = trimmed;
        }

        AgentScopeProperties.ProviderConfig cfg = idx > 0
                ? props.getProviders().get(providerName)
                : props.getActiveProviderConfig();
        if (cfg == null || modelName.isBlank()) {
            log.warn("模型 {} 解析失败（provider 配置缺失），回退全局默认 {}", trimmed, resolveModelId());
            return ModelRegistry.resolve(resolveModelId());
        }

        try {
            String dynamicId = providerName + ":" + modelName;
            io.agentscope.core.model.Model model = buildModel(providerName, cfg, modelName);
            ModelRegistry.register(dynamicId, model);
            log.info("动态注册模型: {} (baseUrl={})", dynamicId, cfg.getBaseUrl());
            return model;
        } catch (Exception e) {
            log.warn("动态构建模型 {} 失败，回退全局默认: {}", trimmed, e.getMessage(), e);
            return ModelRegistry.resolve(resolveModelId());
        }
    }

    /**
     * 当前激活的模型 ID，如 "deepseek:deepseek-chat"
     */
    public String resolveModelId() {
        String provider = props.getModel().getProvider().trim().toLowerCase(Locale.ROOT);
        String modelName = props.getModel().getModelName();
        AgentScopeProperties.ProviderConfig cfg = props.getProviders().get(props.getModel().getProvider());
        if (cfg != null && cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            modelName = cfg.getModelName();
        }
        return provider + ":" + modelName.trim();
    }

    private io.agentscope.core.model.Model buildModel(String providerName,
                                                      AgentScopeProperties.ProviderConfig cfg) {
        return buildModel(providerName, cfg, cfg.getModelName());
    }

    private io.agentscope.core.model.Model buildModel(String providerName,
                                                      AgentScopeProperties.ProviderConfig cfg,
                                                      String modelName) {
        String apiKey = resolveApiKey(providerName, cfg.getApiKey());
        String baseUrl = cfg.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        Boolean stream = props.getModel().getStream();

        HttpTransport transport = new LoggingHttpTransport(new RetryableHttpTransport(HttpTransportFactory.getDefault()));

        return OpenAIChatModel.builder()
                .baseUrl(baseUrl.trim())
                .apiKey(apiKey)
                .modelName(modelName == null ? "" : modelName.trim())
                .stream(stream == null || stream)
           //     .generateOptions(buildGenerateOptions(providerName, cfg, modelName))
                .httpTransport(transport)
                .build();
    }

    /**
     * 构建生成参数。
     * <p>
     * 这里是「大文件 write_file / edit_file 参数丢失」的修复点，必须同时设置两项：
     * <ol>
     *   <li><b>maxTokens</b> —— 不设置时走服务端默认上限（常见 4096）。长文件内容会让
     *       tool_call 的 arguments JSON 超限被截断，收到的分片拼起来形如
     *       {@code {"path":"a.md","content":"....}（缺尾部引号与花括号）。
     *       上游 {@code ToolCallBuilder.build()} 会用
     *       {@code JsonUtils.isValidJsonObject(raw)} 校验，不合法就把整个 arguments
     *       替换成 {@code "{}"}，且只打 debug 日志不抛错 —— 于是工具方法拿到
     *       全 null 的入参，报「未找到 path/content 参数」。同时
     *       {@code maxCompletionTokens} 一并设置以兼容较新的 OpenAI 端点。</li>
     *   <li><b>parallelToolCalls=false</b> —— 上游流式累积器
     *       {@code ToolCallsAccumulator.determineKey()} 对「只有 arguments 分片、
     *       没有 id 和 name」的 chunk 用 {@code lastToolCallKey} 兜底归组。
     *       并行 tool_call 时多路分片交错，会把 B 的 arguments 追加进 A 的
     *       {@code rawContent}，同样导致 JSON 破损 → 降级成 {@code {}}。</li>
     * </ol>
     * <p>
     * <b>route / 网关型 provider 注意</b>：同一 base-url 后面可能挂着 kimi、豆包、
     * deepseek、glm 等模型，各家输出 token 上限差异极大，统一下发一个值会让上限低的
     * 模型直接报 400。因此这里按
     * 「模型名（{@code agentscope.model-limits}）→ provider（{@code agentscope.providers.*.max-tokens}）→ 全局」
     * 三级回退取值，并支持对个别不接受 {@code max_tokens} 的模型完全不下发该参数。
     */
    private GenerateOptions buildGenerateOptions(String providerName,
                                                 AgentScopeProperties.ProviderConfig cfg,
                                                 String modelName) {
        AgentScopeProperties.Model global = props.getModel();
        AgentScopeProperties.ModelLimit limit = resolveModelLimit(providerName, modelName);

        GenerateOptions.Builder builder = GenerateOptions.builder();

        boolean unsupported = limit != null && Boolean.TRUE.equals(limit.getMaxTokensUnsupported());
        if (!unsupported) {
            Integer maxTokens = resolveMaxTokens(providerName, cfg, modelName);
            if (maxTokens != null && maxTokens > 0) {
                builder.maxTokens(maxTokens).maxCompletionTokens(maxTokens);
            }
        }

        Double temperature = firstNonNull(
                cfg == null ? null : cfg.getTemperature(),
                global.getTemperature());
        if (temperature != null) {
            builder.temperature(temperature);
        }

        Boolean parallelToolCalls = firstNonNull(
                limit == null ? null : limit.getParallelToolCalls(),
                cfg == null ? null : cfg.getParallelToolCalls(),
                global.getParallelToolCalls());
        if (parallelToolCalls != null) {
            builder.parallelToolCalls(parallelToolCalls);
        }
        return builder.build();
    }

    /**
     * 解析某个 provider + 模型名最终生效的 max output tokens（三级回退）。
     * <p>
     * 返回 null 表示不下发 {@code max_tokens} 参数（模型声明不支持，或各级均未配置）。
     * 包可见以便单元测试直接断言优先级。
     */
    Integer resolveMaxTokens(String providerName,
                             AgentScopeProperties.ProviderConfig cfg,
                             String modelName) {
        AgentScopeProperties.ModelLimit limit = resolveModelLimit(providerName, modelName);
        if (limit != null && Boolean.TRUE.equals(limit.getMaxTokensUnsupported())) {
            return null;
        }
        return firstNonNull(
                limit == null ? null : limit.getMaxTokens(),
                cfg == null ? null : cfg.getMaxTokens(),
                props.getModel().getMaxTokens());
    }

    /**
     * 按模型名解析生成参数上限，匹配优先级：
     * <ol>
     *   <li>{@code <provider>:<modelName>} 精确匹配（同名模型挂在不同 provider 下时可区分）</li>
     *   <li>{@code <modelName>} 精确匹配</li>
     *   <li>前缀通配 {@code xxx*}，多个命中时取最长前缀（更具体的优先）</li>
     * </ol>
     * 全部未命中返回 null，交由调用方回退 provider 级 / 全局配置。
     */
    AgentScopeProperties.ModelLimit resolveModelLimit(String providerName, String modelName) {
        Map<String, AgentScopeProperties.ModelLimit> limits = props.getModelLimits();
        if (limits == null || limits.isEmpty() || modelName == null || modelName.isBlank()) {
            return null;
        }
        String name = modelName.trim().toLowerCase(Locale.ROOT);
        String qualified = (providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT)) + ":" + name;

        AgentScopeProperties.ModelLimit best = null;
        int bestPrefixLen = -1;
        for (var entry : limits.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(qualified)) {
                return entry.getValue();
            }
            if (key.equals(name)) {
                // 精确匹配模型名：记住它，但仍让 qualified 精确匹配有机会胜出
                best = entry.getValue();
                bestPrefixLen = Integer.MAX_VALUE;
                continue;
            }
            if (key.endsWith("*")) {
                String prefix = key.substring(0, key.length() - 1);
                if (!prefix.isEmpty() && name.startsWith(prefix)
                        && bestPrefixLen != Integer.MAX_VALUE && prefix.length() > bestPrefixLen) {
                    best = entry.getValue();
                    bestPrefixLen = prefix.length();
                }
            }
        }
        return best;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * 解析 API Key：优先配置值，其次环境变量 {@code <PROVIDER>_API_KEY}，
     * 最后回退到 OPENAI_API_KEY（OpenAI 兼容协议统一约定）
     */
    private String resolveApiKey(String providerName, String configured) {
        log.info("resolveApiKey: provider={}, configured='{}'", providerName, configured);
        if (configured != null && !configured.isBlank()
                && !configured.startsWith("${") && !configured.contains("your-api-key")) {
            return configured;
        }
        String envName = providerName.toUpperCase(Locale.ROOT) + "_API_KEY";
        // .env 文件加载为系统属性，环境变量优先
        String fromProp = System.getProperty(envName);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            return openaiKey;
        }
        String openaiProp = System.getProperty("OPENAI_API_KEY");
        if (openaiProp != null && !openaiProp.isBlank()) {
            return openaiProp;
        }
        return configured != null ? configured : "";
    }
}
