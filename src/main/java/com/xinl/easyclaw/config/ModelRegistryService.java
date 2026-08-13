package com.xinl.easyclaw.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    /* TODO: migrate to Embabel - AgentScopeProperties removed, ModelRegistry removed */
    public ModelRegistryService() {
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized void reload() {
        /* TODO: migrate to Embabel - ModelRegistry.register disabled */
        log.info("ModelRegistryService.reload() called (no-op pending Embabel migration)");
    }

    public Object resolveOrBuild(String modelId) {
        /* TODO: migrate to Embabel - ModelRegistry.resolve disabled */
        log.warn("resolveOrBuild not implemented yet: {}", modelId);
        return null;
    }

    public String resolveModelId() {
        /* TODO: migrate to Embabel */
        return "unknown:unknown";
    }

    private String resolveApiKey(String providerName, String configured) {
        log.info("resolveApiKey: provider={}, configured='{}'", providerName, configured);
        if (configured != null && !configured.isBlank()
                && !configured.startsWith("${") && !configured.contains("your-api-key")) {
            return configured;
        }
        String envName = providerName.toUpperCase(Locale.ROOT) + "_API_KEY";
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
