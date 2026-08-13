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

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final Path externalConfig = SystemHomePaths.systemHome().resolve("application.yml");
    private final ModelRegistryService modelRegistryService;
    private final LoggingSystem loggingSystem;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ReentrantLock lock = new ReentrantLock();

    /* TODO: migrate to Embabel - AgentScopeProperties removed */
    public SettingsService(ModelRegistryService modelRegistryService,
                           LoggingSystem loggingSystem) {
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

    @SuppressWarnings("unchecked")
    public void saveRawYaml(String yaml) throws IOException {
        lock.lock();
        try {
            Files.writeString(externalConfig, yaml, StandardCharsets.UTF_8);
            log.info("已保存配置到 {}", externalConfig);

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

            /* TODO: migrate to Embabel - agentscope config merge disabled */
            Object agentscope = root.get("agentscope");
            if (agentscope instanceof Map) {
                try {
                    modelRegistryService.reload();
                } catch (Exception e) {
                    log.warn("reload failed: {}", e.getMessage());
                }
            }

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
