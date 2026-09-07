package com.xinl.easyclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据库目录初始化器
 * <p>
 * 在 Spring 上下文创建之前（{@link EnvironmentPostProcessor} 阶段）确保 SQLite
 * 数据库文件所在目录存在。SQLite 驱动不会自动创建父目录，若目录缺失会导致 Hibernate
 * DDL 阶段抛出 "path does not exist" 异常，应用启动失败。
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}
 * 注册，早于所有 Bean（包括 DataSource、EntityManagerFactory）的创建，因此可保证目录
 * 在 Hibernate 建立连接前就绪。
 */
public class DatabaseDirectoryInitializer implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDirectoryInitializer.class);

    private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        setupAgentScopePaths();

        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(SQLITE_URL_PREFIX)) {
            return;
        }

        String dbPath = url.substring(SQLITE_URL_PREFIX.length());
        // 兼容带查询参数的 URL，例如 jdbc:sqlite:./data/ai.db?foreign_keys=true
        int queryIndex = dbPath.indexOf('?');
        if (queryIndex >= 0) {
            dbPath = dbPath.substring(0, queryIndex);
        }

        Path parent = Paths.get(dbPath).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }

        if (!Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
                log.info("已创建数据库目录: {}", parent);
            } catch (IOException ex) {
                throw new IllegalStateException("无法创建数据库目录: " + parent, ex);
            }
        }
    }

    /**
     * AgentScope 框架路径统一收敛：所有框架生成的文件只能落在两级目录——
     * <ul>
     *   <li>全局：{@code ~/.easyClaw/}（通过系统属性 {@code agentscope.state.home} 强制覆盖默认的 {@code ~/.agentscope/state}）</li>
     *   <li>项目：{@code <workspace>/.easyClaw/agent/}（HarnessAgent.Builder 显式指定 workspace + stateStore）</li>
     * </ul>
     * 禁止框架在工作区根或用户家目录散落 {@code .agentscope}、{@code .index} 等额外目录。
     */
    private void setupAgentScopePaths() {
        Path systemHome = Paths.get(System.getProperty("user.home"), ".easyClaw").toAbsolutePath().normalize();
        try {
            Files.createDirectories(systemHome);
        } catch (IOException e) {
            log.warn("无法创建系统目录 {}: {}", systemHome, e.getMessage());
        }

        // 覆盖 AgentScope 默认状态目录（默认是 ~/.agentscope/state/<agentId>）
        // 统一落到 ~/.easyClaw/state/<agentId>，和 SQLite 数据库、全局 skills 同级
        String existing = System.getProperty("agentscope.state.home");
        if (existing == null || existing.isBlank()) {
            String stateHome = systemHome.resolve("state").toString();
            System.setProperty("agentscope.state.home", stateHome);
            log.info("AgentScope 状态目录已锁定: agentscope.state.home = {}", stateHome);
        } else {
            log.info("AgentScope 状态目录沿用系统属性: agentscope.state.home = {}", existing);
        }

        // 同样覆盖 AgentScope 可能读取的其他默认路径系统属性（防御性）
        setIfAbsent("agentscope.home", systemHome.toString());
    }

    private static void setIfAbsent(String key, String value) {
        String existing = System.getProperty(key);
        if (existing == null || existing.isBlank()) {
            System.setProperty(key, value);
        }
    }
}
