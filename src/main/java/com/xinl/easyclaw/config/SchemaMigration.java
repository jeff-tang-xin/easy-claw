package com.xinl.easyclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite 轻量 schema 版本管控（自建版 Flyway）。
 * <p>
 * SQLite 不支持 ALTER COLUMN / DROP COLUMN，Hibernate ddl-auto=update
 * 也不会改已有列约束。每次 schema 演进在这里加一个"迁移版本"。
 * <p>
 * 每个迁移：检测 →（必要时 DROP 单表让 Hibernate 重建）→ 写版本号。
 * 已执行过的版本不会重复执行。
 */
@Configuration
@Profile("dev-sqlite")
public class SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private static final int CURRENT_VERSION = 5;

    public SchemaMigration(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            initVersionTable(conn);

            int applied = getAppliedVersion(conn);
            log.info("[SchemaMigration] 当前已应用版本: {}, 目标版本: {}", applied, CURRENT_VERSION);

            if (applied < 1) {
                migrateV1(conn);
                setVersion(conn, 1);
            }

            if (applied < 2) {
                migrateV2(conn);
                setVersion(conn, 2);
            }

            if (applied < 3) {
                migrateV3(conn);
                setVersion(conn, 3);
            }

            if (applied < 4) {
                migrateV4(conn);
                setVersion(conn, 4);
            }

            if (applied < 5) {
                migrateV5(conn);
                setVersion(conn, 5);
            }

        } catch (Exception e) {
            log.warn("[SchemaMigration] 迁移失败（不阻塞启动，但 schema 可能不一致）: {}", e.getMessage());
        }
    }

    // ======================== 迁移实现 ========================

    /**
     * v1: 旧版 mcp_services 有 sse_url NOT NULL 列，实体已删除该字段。
     * SQLite 无法 ALTER COLUMN，只能 DROP 单表让 Hibernate 按实体重建。
     */
    private void migrateV1(Connection conn) throws Exception {
        if (!tableExists(conn, "mcp_services")) {
            log.info("[SchemaMigration.v1] mcp_services 不存在，跳过（Hibernate 会自动建）。");
            return;
        }

        if (hasOldSseUrl(conn)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE mcp_services");
            }
            log.info("[SchemaMigration.v1] 已删除旧版 mcp_services（含 NOT NULL sse_url），Hibernate 将重建。");
        } else {
            log.info("[SchemaMigration.v1] mcp_services 已是新版结构，跳过。");
        }
    }

    private void migrateV2(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            boolean hasIntent = false;
            boolean hasActiveSkills = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(workspaces)")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("intent".equalsIgnoreCase(name)) hasIntent = true;
                    if ("active_skills".equalsIgnoreCase(name)) hasActiveSkills = true;
                }
            }
            if (!hasIntent) {
                stmt.execute("ALTER TABLE workspaces ADD COLUMN intent VARCHAR(32) DEFAULT 'general'");
                log.info("[SchemaMigration.v2] 已添加 workspaces.intent 列");
            }
            if (!hasActiveSkills) {
                stmt.execute("ALTER TABLE workspaces ADD COLUMN active_skills TEXT");
                log.info("[SchemaMigration.v2] 已添加 workspaces.active_skills 列");
            }
            if (hasIntent && hasActiveSkills) {
                log.info("[SchemaMigration.v2] workspaces 已是新版结构，跳过。");
            }
        }
    }

    private void migrateV3(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // 1. workspaces.scenario_id
            boolean hasScenarioId = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(workspaces)")) {
                while (rs.next()) {
                    if ("scenario_id".equalsIgnoreCase(rs.getString("name"))) hasScenarioId = true;
                }
            }
            if (!hasScenarioId) {
                stmt.execute("ALTER TABLE workspaces ADD COLUMN scenario_id VARCHAR(64)");
                log.info("[SchemaMigration.v3] 已添加 workspaces.scenario_id 列");
            }

            // 2. scenarios 表（Hibernate 会自动建，但迁移里先确保存在）
            boolean hasScenarios = tableExists(conn, "scenarios");
            if (!hasScenarios) {
                stmt.execute("CREATE TABLE IF NOT EXISTS scenarios ("
                        + "id VARCHAR(64) PRIMARY KEY, "
                        + "name VARCHAR(128) NOT NULL, "
                        + "description VARCHAR(500), "
                        + "icon VARCHAR(32), "
                        + "owner_id VARCHAR(64), "
                        + "intent VARCHAR(32), "
                        + "action_bindings TEXT, "
                        + "mcp_bindings TEXT, "
                        + "skills TEXT, "
                        + "is_preset BOOLEAN, "
                        + "created_at TIMESTAMP, "
                        + "updated_at TIMESTAMP"
                        + ")");
                log.info("[SchemaMigration.v3] 已创建 scenarios 表");
            }
        }
    }

    private void migrateV4(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            boolean hasEnabledAgents = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(scenarios)")) {
                while (rs.next()) {
                    if ("enabled_agents".equalsIgnoreCase(rs.getString("name"))) hasEnabledAgents = true;
                }
            }
            if (!hasEnabledAgents) {
                stmt.execute("ALTER TABLE scenarios ADD COLUMN enabled_agents TEXT");
                log.info("[SchemaMigration.v4] 已添加 scenarios.enabled_agents 列");
            }
        }
    }

    private void migrateV5(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // mcp_tools 表
            boolean hasMcpTools = tableExists(conn, "mcp_tools");
            if (!hasMcpTools) {
                stmt.execute("CREATE TABLE IF NOT EXISTS mcp_tools ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "service_id INTEGER NOT NULL REFERENCES mcp_services(id), "
                        + "tool_name VARCHAR(64) NOT NULL, "
                        + "display_name VARCHAR(128), "
                        + "description VARCHAR(500), "
                        + "tool_config TEXT, "
                        + "enabled BOOLEAN DEFAULT 1, "
                        + "sort_order INTEGER DEFAULT 0, "
                        + "created_at TIMESTAMP"
                        + ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mcp_tools_service ON mcp_tools(service_id)");
                log.info("[SchemaMigration.v5] 已创建 mcp_tools 表");
            }

            // 迁移旧数据：把 HTTP_TOOL 类型 McpService 的 implementationConfig 复制到 mcp_tools
            // 注意：需要 JdbcTemplate 或 EntityManager 才能方便地 JOIN，这里只建表，数据迁移在 DataInitializer 中用 JPA 做
            log.info("[SchemaMigration.v5] mcp_tools 表已就绪，历史数据迁移由 SystemDataSeeder 完成");
        }
    }

    // ======================== 工具方法 ========================

    private void initVersionTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER PRIMARY KEY, "
                    + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
        }
    }

    private int getAppliedVersion(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setVersion(Connection conn, int version) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
        log.info("[SchemaMigration] 已记录版本 v{}", version);
    }

    private boolean tableExists(Connection conn, String table) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean hasOldSseUrl(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(mcp_services)")) {
            while (rs.next()) {
                String name = rs.getString("name");
                int notnull = rs.getInt("notnull");
                if ("sse_url".equalsIgnoreCase(name) && notnull == 1) return true;
            }
        }
        return false;
    }
}
