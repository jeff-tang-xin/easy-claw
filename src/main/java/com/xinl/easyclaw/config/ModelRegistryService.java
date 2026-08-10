package com.xinl.easyclaw.config;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

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
            // 裸 model name → 用当前激活的协议凭证
            providerName = props.getModel().getProvider().trim().toLowerCase(Locale.ROOT);
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
            if (ModelRegistry.canResolve(dynamicId)) {
                return ModelRegistry.resolve(dynamicId);
            }
            io.agentscope.core.model.Model model = buildModel(providerName, cfg, modelName);
            ModelRegistry.register(dynamicId, model);
            log.info("动态注册模型: {} (baseUrl={})", dynamicId, cfg.getBaseUrl());
            return model;
        } catch (Exception e) {
            log.warn("动态构建模型 {} 失败，回退全局默认: {}", trimmed, e.getMessage());
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

        HttpTransport transport = new RetryableHttpTransport(HttpTransportFactory.getDefault());

        return OpenAIChatModel.builder()
                .baseUrl(baseUrl.trim())
                .apiKey(apiKey)
                .modelName(modelName == null ? "" : modelName.trim())
                .stream(stream == null || stream)
                .httpTransport(transport)
                .build();
    }

    /**
     * 解析 API Key：优先配置值，其次环境变量 {@code <PROVIDER>_API_KEY}，
     * 最后回退到 OPENAI_API_KEY（OpenAI 兼容协议统一约定）
     */
    private String resolveApiKey(String providerName, String configured) {
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
