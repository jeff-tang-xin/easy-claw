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

    private static final int CURRENT_VERSION = 3;

    public SchemaMigration(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            initVersionTable(conn);

            int applied = getAppliedVersion(conn);
            log.info("[SchemaMigration] 当前已应用版本: {}, 目标版本: {}", applied, CURRENT_VERSION);

            // v1: 初始迁移 —— 干掉旧版带 sse_url NOT NULL 的 mcp_services
            if (applied < 1) {
                migrateV1(conn);
                setVersion(conn, 1);
            }

            // v2: scenarios 表补 mode 列（SQLite ddl-auto=update 不会自动加列）
            if (applied < 2) {
                migrateV2(conn);
                setVersion(conn, 2);
            }

            // v3: scenarios 表补 skill/subagent/MCP 绑定四列
            if (applied < 3) {
                migrateV3(conn);
                setVersion(conn, 3);
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

    /**
     * v2: scenarios 表补 mode 列 + mcp_services 补 enabled_tools 列。
     * SQLite 的 Hibernate ddl-auto=update 不会自动加列。
     */
    private void migrateV2(Connection conn) throws Exception {
        if (tableExists(conn, "scenarios") && !columnExists(conn, "scenarios", "mode")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE scenarios ADD COLUMN mode TEXT NOT NULL DEFAULT 'single'");
            }
            log.info("[SchemaMigration.v2] 已添加 scenarios.mode 列。");
        }
        if (tableExists(conn, "mcp_services") && !columnExists(conn, "mcp_services", "enabled_tools")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE mcp_services ADD COLUMN enabled_tools TEXT");
            }
            log.info("[SchemaMigration.v2] 已添加 mcp_services.enabled_tools 列。");
        }
    }

    /**
     * v3: scenarios 表补场景能力绑定列 —— skills / subagents / mcp_services / capability_tier。
     * <p>全部可空，不设默认值：NULL = 未绑定 = 不限制（向后兼容既有场景）。
     */
    private void migrateV3(Connection conn) throws Exception {
        if (!tableExists(conn, "scenarios")) {
            log.info("[SchemaMigration.v3] scenarios 不存在，跳过（Hibernate 会按实体建）。");
            return;
        }
        addColumnIfAbsent(conn, "scenarios", "skills", "TEXT");
        addColumnIfAbsent(conn, "scenarios", "subagents", "TEXT");
        addColumnIfAbsent(conn, "scenarios", "mcp_services", "TEXT");
        addColumnIfAbsent(conn, "scenarios", "capability_tier", "TEXT");
    }

    /** 幂等加列：已存在则跳过，避免重复迁移报错 */
    private void addColumnIfAbsent(Connection conn, String table, String column, String type)
            throws Exception {
        if (columnExists(conn, table, column)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
        log.info("[SchemaMigration.v3] 已添加 {}.{} 列。", table, column);
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

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
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
