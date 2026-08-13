package com.xinl.easyclaw;

import com.embabel.agent.config.annotation.EnableAgents;
import com.xinl.easyclaw.config.SystemHomePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Easy-Claw AI 助手启动类
 * <p>
 * 基于 Embabel 1.0 GOAP 框架构建的可配置 AI 助手系统，
 * 支持目标驱动规划、强类型领域模型、MCP 集成、三级记忆管理。
 * React 前端（Vite 产物打进 static）通过 REST + WebSocket 与后端交互。
 * <p>
 * 目录约定：
 * <ul>
 *   <li>系统级（运行配置 / SQLite 元数据库 / 初始化文件）→ {@code ~/.easyClaw}</li>
 *   <li>Workspace 级（Agent 状态 / 对话记录 / 记忆）→ 用户创建时指定的工作目录下的 {@code .easyClaw/agent/}</li>
 * </ul>
 */
@SpringBootApplication
@EnableAgents
public class AiAssistantApplication {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantApplication.class);

    public static void main(String[] args) {
        Path home = ensureSystemHome();
        migratePermissionTable(home);
        SpringApplication app = new SpringApplication(AiAssistantApplication.class);
        // 外部配置优先：加载 ~/.easyClaw/application.yml（不存在则首次从 classpath 初始化复制）
        app.setDefaultProperties(Map.of(
                "spring.config.additional-location", "file:" + home + "/"
        ));
        ConfigurableApplicationContext ctx = app.run(args);
        printBanner(ctx.getEnvironment());
    }

    /**
     * 初始化系统运行目录 ~/.easyClaw：从旧目录 ~/.easy-ai 迁移（若有），
     * 创建全局 Skills/子 Agent 目录，并将内置 application.yml 复制为可编辑的外部配置。
     */
    private static Path ensureSystemHome() {
        Path home = SystemHomePaths.systemHome();
        try {
            // 旧目录迁移：~/.easy-ai → ~/.easyClaw（保留数据库/配置/全局能力）
            Path legacy = SystemHomePaths.legacySystemHome();
            if (!Files.exists(home) && Files.exists(legacy)) {
                Files.move(legacy, home);
                log.info("已迁移系统目录: {} → {}", legacy, home);
                // 修正迁移后的外部配置中的旧路径
                Path external = home.resolve("application.yml");
                if (Files.exists(external)) {
                    String content = Files.readString(external)
                            .replace(".easy-ai", ".easyClaw");
                    Files.writeString(external, content);
                }
            }
            Files.createDirectories(home);
            Files.createDirectories(home.resolve("skills"));
            Files.createDirectories(home.resolve("subagents"));
            seedBundledSubagents(home.resolve("subagents"));
            Path externalConfig = home.resolve("application.yml");
            if (!Files.exists(externalConfig)) {
                try (InputStream in = AiAssistantApplication.class.getResourceAsStream("/application.yml")) {
                    if (in != null) {
                        Files.copy(in, externalConfig, StandardCopyOption.REPLACE_EXISTING);
                        log.info("已初始化外部配置: {}", externalConfig);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("初始化系统目录 ~/.easyClaw 失败: {}", e.getMessage());
        }
        return home;
    }

    /**
     * 迁移 permission_rules 表：新增 workspace_id 列（安全门禁按工作区隔离）。
     * 旧表结构无该列 → 重建表并保留旧规则为全局默认（workspace_id = NULL）；
     * 同时去掉 tool_name 的唯一约束（改为按 workspace+tool 逻辑唯一）。
     */
    private static void migratePermissionTable(Path home) {
        String url = "jdbc:sqlite:" + home.resolve("ai-assistant.db");
        try (var conn = java.sql.DriverManager.getConnection(url)) {
            boolean tableExists = false;
            boolean hasWorkspaceColumn = false;
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='permission_rules'")) {
                tableExists = rs.next();
            }
            if (tableExists) {
                try (var rs = conn.createStatement().executeQuery("PRAGMA table_info(permission_rules)")) {
                    while (rs.next()) {
                        if ("workspace_id".equalsIgnoreCase(rs.getString("name"))) {
                            hasWorkspaceColumn = true;
                            break;
                        }
                    }
                }
            }
            if (!tableExists || hasWorkspaceColumn) {
                return; // 新表由 Hibernate 创建；已迁移则跳过
            }
            conn.createStatement().execute("""
                    ALTER TABLE permission_rules RENAME TO permission_rules_old;
                    CREATE TABLE permission_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_id VARCHAR(100),
                        tool_name VARCHAR(100) NOT NULL,
                        behavior VARCHAR(20),
                        source VARCHAR(50),
                        created_at TIMESTAMP
                    );
                    INSERT INTO permission_rules (id, workspace_id, tool_name, behavior, source, created_at)
                        SELECT id, NULL, tool_name, behavior, source, created_at FROM permission_rules_old;
                    DROP TABLE permission_rules_old;
                    """);
            log.info("已迁移 permission_rules 表：新增 workspace_id 列，旧规则保留为全局默认");
        } catch (Exception e) {
            log.warn("permission_rules 表迁移失败（可忽略）: {}", e.getMessage());
        }
    }

    private static void printBanner(Environment env) {
        String port = env.getProperty("server.port", "8080");
        String address = env.getProperty("server.address", "localhost");
        System.out.printf("""
                
                ╔═══════════════════════════════════════════════════╗
                ║                                                 ║
                ║   🤖 Easy-Claw AI 助手启动成功！                   ║
                ║   🔧 Powered by Embabel 1.0 (GOAP)               ║
                ║                                                 ║
                ║   🌐 访问地址: http://%s:%s            ║
                ║   💾 系统目录: ~/.easyClaw                      ║
                ║   📁 工作区: 用户指定目录(.easyClaw/agent 内)  ║
                ║                                                 ║
                ║   功能模块:                                      ║
                ║   ├── 💬 智能对话 (多 Agent 编排 + 流式输出)     ║
                ║   ├── 📚 Skills 与子 Agent 管理                  ║
                ║   ├── 🎭 角色管理 / 🔧 工具管理                  ║
                ║   ├── 🔌 MCP 服务 / ⚙️ 系统设置                  ║
                ║   └── 🧠 上下文压缩 + 会话记忆保留               ║
                ║                                                 ║
                ╚═══════════════════════════════════════════════════╝
                %n""", address, port);
    }

    /**
     * 将 JAR 内置的 3 个全局子 Agent 模板播种到 ~/.easyClaw/subagents/。
     * 只复制目标文件不存在的情况（不覆盖用户可能已修改的版本）。
     */
    private static void seedBundledSubagents(Path targetDir) {
        String[] bundled = {"code-expert", "file-expert", "researcher"};
        for (String name : bundled) {
            Path target = targetDir.resolve(name + ".md");
            if (Files.exists(target)) continue;
            try (InputStream in = AiAssistantApplication.class.getResourceAsStream("/subagents/" + name + ".md")) {
                if (in != null) {
                    Files.copy(in, target);
                    log.info("已播种内置子 Agent: {}", name);
                }
            } catch (IOException e) {
                log.warn("播种内置子 Agent {} 失败（可忽略）: {}", name, e.getMessage());
            }
        }
    }
}
