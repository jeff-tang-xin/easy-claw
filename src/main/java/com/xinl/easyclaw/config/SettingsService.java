package com.xinl.easyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户设置服务 — 直接读写 ~/.easyClaw/application.yml 原文。
 * <p>
 * 不解析、不展开占位符、不做字段映射。前端看到什么 YAML，保存时就写什么 YAML。
 * 保存后从文件重新解析 agentscope 节点合并到 AgentScopeProperties 并热重载。
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final Path externalConfig = SystemHomePaths.systemHome().resolve("application.yml");
    private final AgentScopeProperties props;
    private final ModelRegistryService modelRegistryService;
    private final LoggingSystem loggingSystem;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ReentrantLock lock = new ReentrantLock();

    public SettingsService(AgentScopeProperties props,
                           ModelRegistryService modelRegistryService,
                           LoggingSystem loggingSystem) {
        this.props = props;
        this.modelRegistryService = modelRegistryService;
        this.loggingSystem = loggingSystem;
    }

    public Path getExternalConfigPath() {
        return externalConfig;
    }

    public String readRawYaml() {
        lock.lock();
        try {
            if (!Files.exists(externalConfig)) {
                return "# 暂无配置文件，将使用 jar 内默认值\n";
            }
            return Files.readString(externalConfig, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取配置文件失败: {}", e.getMessage());
            return "";
        } finally {
            lock.unlock();
        }
    }

    /**
     * 验证 YAML 格式是否合法（解析通过即可）。
     *
     * @return null 表示合法，否则返回错误信息
     */
    public String validateYaml(String yaml) {
        if (yaml == null || yaml.isBlank()) return null;
        try {
            String firstDoc = yaml.trim();
            int sep = firstDoc.indexOf("\n---");
            if (sep > 0) firstDoc = firstDoc.substring(0, sep);
            yamlMapper.readValue(firstDoc.trim(), Object.class);
            return null;
        } catch (IOException e) {
            return "YAML 解析错误: " + e.getMessage();
        }
    }

    /**
     * 保存 YAML 原文到文件，然后从文件重新解析 agentscope 节点合并到内存并热重载。
     *
     * @throws IOException 写入失败
     */
    @SuppressWarnings("unchecked")
    public void saveRawYaml(String yaml) throws IOException {
        lock.lock();
        try {
            Files.writeString(externalConfig, yaml, StandardCharsets.UTF_8);
            log.info("已保存配置到 {}", externalConfig);

            // 从刚写入的文件解析 agentscope → 合并到 AgentScopeProperties
            String firstDoc = yaml.trim();
            int sep = firstDoc.indexOf("\n---");
            if (sep > 0) firstDoc = firstDoc.substring(0, sep);
            Object parsed;
            try {
                parsed = yamlMapper.readValue(firstDoc.trim(), Object.class);
            } catch (IOException e) {
                log.warn("保存后解析失败（但文件已写入）: {}", e.getMessage());
                return;
            }
            if (!(parsed instanceof Map)) return;

            Map<String, Object> root = (Map<String, Object>) parsed;

            // 合并 agentscope
            Object agentscope = root.get("agentscope");
            if (agentscope instanceof Map) {
                Map<String, Object> agentscopeMap = (Map<String, Object>) agentscope;
                Object model = agentscopeMap.get("model");
                if (model instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) model;
                    AgentScopeProperties.Model target = props.getModel();
                    if (m.containsKey("provider")) target.setProvider(str(m.get("provider")));
                    if (m.containsKey("api-key")) target.setApiKey(str(m.get("api-key")));
                    if (m.containsKey("base-url")) target.setBaseUrl(str(m.get("base-url")));
                    if (m.containsKey("model-name")) target.setModelName(str(m.get("model-name")));
                    if (m.containsKey("default-model")) target.setDefaultModel(str(m.get("default-model")));
                    if (m.containsKey("temperature")) {
                        Object t = m.get("temperature");
                        if (t instanceof Number n) target.setTemperature(n.doubleValue());
                    }
                    if (m.containsKey("stream")) {
                        Object s = m.get("stream");
                        if (s instanceof Boolean b) target.setStream(b);
                    }
                    if (m.containsKey("max-tokens")) {
                        Object t = m.get("max-tokens");
                        if (t instanceof Number n) target.setMaxTokens(n.intValue());
                    }
                    if (m.containsKey("parallel-tool-calls")) {
                        Object t = m.get("parallel-tool-calls");
                        if (t instanceof Boolean b) target.setParallelToolCalls(b);
                    }
                }
                Object providers = agentscopeMap.get("providers");
                if (providers instanceof Map) {
                    Map<String, Object> pm = (Map<String, Object>) providers;
                    props.getProviders().clear();
                    for (var e : pm.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> pv) {
                            AgentScopeProperties.ProviderConfig pc = new AgentScopeProperties.ProviderConfig();
                            if (pv.containsKey("api-key")) pc.setApiKey(str(pv.get("api-key")));
                            if (pv.containsKey("base-url")) pc.setBaseUrl(str(pv.get("base-url")));
                            if (pv.containsKey("model-name")) pc.setModelName(str(pv.get("model-name")));
                            if (pv.containsKey("temperature")) {
                                Object t = pv.get("temperature");
                                if (t instanceof Number n) pc.setTemperature(n.doubleValue());
                            }
                            if (pv.containsKey("max-tokens")) {
                                Object t = pv.get("max-tokens");
                                if (t instanceof Number n) pc.setMaxTokens(n.intValue());
                            }
                            if (pv.containsKey("parallel-tool-calls")) {
                                Object t = pv.get("parallel-tool-calls");
                                if (t instanceof Boolean b) pc.setParallelToolCalls(b);
                            }
                            props.getProviders().put(e.getKey(), pc);
                        }
                    }
                }
                Object modelLimits = agentscopeMap.get("model-limits");
                if (modelLimits instanceof Map) {
                    Map<String, Object> lm = (Map<String, Object>) modelLimits;
                    props.getModelLimits().clear();
                    for (var e : lm.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> lv) {
                            AgentScopeProperties.ModelLimit ml = new AgentScopeProperties.ModelLimit();
                            if (lv.get("max-tokens") instanceof Number n) ml.setMaxTokens(n.intValue());
                            if (lv.get("parallel-tool-calls") instanceof Boolean b) ml.setParallelToolCalls(b);
                            if (lv.get("max-tokens-unsupported") instanceof Boolean b) ml.setMaxTokensUnsupported(b);
                            props.getModelLimits().put(e.getKey(), ml);
                        }
                    }
                }
                modelRegistryService.reload();
            }

            // 热修改日志级别
            Object loggingNode = root.get("logging");
            if (loggingNode instanceof Map) {
                Object levelNode = ((Map<String, Object>) loggingNode).get("level");
                if (levelNode instanceof Map) {
                    for (var e : ((Map<String, Object>) levelNode).entrySet()) {
                        try {
                            LogLevel level = LogLevel.valueOf(e.getValue().toString().toUpperCase());
                            loggingSystem.setLogLevel(e.getKey(), level);
                        } catch (IllegalArgumentException ex) {
                            log.warn("无效日志级别 {}: {}", e.getValue(), ex.getMessage());
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
