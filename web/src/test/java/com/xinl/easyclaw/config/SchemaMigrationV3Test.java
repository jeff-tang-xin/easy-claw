package com.xinl.easyclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link SchemaMigration} v3 迁移测试（scenarios 补场景绑定四列）。
 * <p>
 * SQLite 在 {@code ddl-auto=update} 下<b>不会为已存在的表加列</b>，
 * 所以老用户库里的 scenarios 缺列会导致启动后查询直接报错。
 * 这里用真实 SQLite 文件库验证升级路径，而非只断言代码结构。
 */
class SchemaMigrationV3Test {

    private DataSource dataSourceOf(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    /** 建一个 v2 时代的 scenarios 表（有 mode，无绑定列） */
    private void createLegacyScenarios(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE scenarios (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        display_name TEXT,
                        icon TEXT,
                        description TEXT,
                        mode TEXT NOT NULL DEFAULT 'single',
                        system_prompt TEXT,
                        workflow TEXT,
                        is_active INTEGER,
                        is_builtin INTEGER,
                        created_at TEXT,
                        updated_at TEXT
                    )
                    """);
            stmt.executeUpdate(
                    "INSERT INTO scenarios (name, display_name, mode, system_prompt) "
                            + "VALUES ('coding', '通用编程', 'single', '你是编程助手')");
        }
    }

    private List<String> columnsOf(DataSource ds, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private int versionOf(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) AS v FROM schema_version")) {
            return rs.next() ? rs.getInt("v") : -1;
        }
    }

    @Test
    void 旧库升级后应补齐四个绑定列(@TempDir Path tmp) throws Exception {
        DataSource ds = dataSourceOf(tmp.resolve("legacy.db"));
        createLegacyScenarios(ds);
        assertThat(columnsOf(ds, "scenarios")).doesNotContain("skills", "mcp_services");

        new SchemaMigration(ds);

        assertThat(columnsOf(ds, "scenarios"))
                .contains("skills", "subagents", "mcp_services", "capability_tier");
    }

    @Test
    void 升级不得丢失既有场景数据(@TempDir Path tmp) throws Exception {
        DataSource ds = dataSourceOf(tmp.resolve("keep.db"));
        createLegacyScenarios(ds);

        new SchemaMigration(ds);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name, display_name, system_prompt, skills FROM scenarios")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("coding");
            assertThat(rs.getString("display_name")).isEqualTo("通用编程");
            assertThat(rs.getString("system_prompt")).isEqualTo("你是编程助手");
            // 新列对老数据应为 NULL —— 即「未绑定 = 不限制」
            assertThat(rs.getString("skills")).isNull();
        }
    }

    @Test
    void 重复迁移应幂等不报错(@TempDir Path tmp) throws Exception {
        DataSource ds = dataSourceOf(tmp.resolve("idem.db"));
        createLegacyScenarios(ds);

        new SchemaMigration(ds);
        int firstVersion = versionOf(ds);

        // 模拟应用重启：同一个库再迁一次
        assertThatCode(() -> new SchemaMigration(ds)).doesNotThrowAnyException();

        assertThat(versionOf(ds)).isEqualTo(firstVersion);
        assertThat(columnsOf(ds, "scenarios"))
                .containsOnlyOnce("skills")
                .containsOnlyOnce("mcp_services");
    }

    @Test
    void 迁移后版本号应推进到3(@TempDir Path tmp) throws Exception {
        DataSource ds = dataSourceOf(tmp.resolve("ver.db"));
        createLegacyScenarios(ds);

        new SchemaMigration(ds);

        assertThat(versionOf(ds)).isEqualTo(3);
    }

    @Test
    void scenarios表不存在时应跳过而不阻塞启动(@TempDir Path tmp) throws Exception {
        // 全新安装：表还没建，Hibernate 稍后会按实体创建
        DataSource ds = dataSourceOf(tmp.resolve("fresh.db"));

        assertThatCode(() -> new SchemaMigration(ds)).doesNotThrowAnyException();

        assertThat(versionOf(ds)).isEqualTo(3);
    }
}
