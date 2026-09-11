package com.xinl.easyclaw.config.seed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@link SystemDataSeeder#backfillWorkspaceType()} 的存量工作区归档迁移。
 * <p>
 * 用真实的 SQLite 临时文件库（而非内存库：SQLite 每个内存连接各自独立，
 * 而迁移实现内部每次都新开连接），断言：
 * <ul>
 *   <li>NULL / 空串 / 纯空白 type 的存量行被写成 single</li>
 *   <li>已显式归类为 team / schedule 的行保持不动</li>
 *   <li>迁移幂等：第二次执行不报错、不改动任何行</li>
 * </ul>
 * 回填方法为私有，通过反射调用。
 */
class WorkspaceTypeBackfillTest {

    private Path dbFile;
    private String jdbcUrl;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("ws-type-backfill", ".db");
        jdbcUrl = "jdbc:sqlite:" + dbFile.toString().replace('\\', '/');
        // DriverManagerDataSource 每次 getConnection 返回新连接，模拟连接池行为
        // （迁移实现内部 try-with-resources 自行开关连接）
        dataSource = new DriverManagerDataSource(jdbcUrl);

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            // 仅建迁移涉及的最小表结构；type 刻意允许 NULL，模拟 ddl-auto 后补列的存量状态
            st.executeUpdate("CREATE TABLE workspaces (id TEXT PRIMARY KEY, type TEXT)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("存量工作区回填：NULL/空串/空白 → single，已归类的 team/schedule 不动，且幂等")
    void backfillsBlankTypesAndKeepsExplicitTypes() throws Exception {
        // 存量：null / 空串 / 空白 三种待归档；team / schedule 两种已显式归类
        insert("ws-null", null);
        insert("ws-empty", "");
        insert("ws-blank", "   ");
        insert("ws-team", "team");
        insert("ws-sched", "schedule");

        invokeBackfill();

        Map<String, String> after1 = readAll();
        assertEquals("single", after1.get("ws-null"));
        assertEquals("single", after1.get("ws-empty"));
        assertEquals("single", after1.get("ws-blank"));
        assertEquals("team", after1.get("ws-team"));
        assertEquals("schedule", after1.get("ws-sched"));

        // 幂等：再次执行不抛异常，且所有行维持第一次回填后的结果
        invokeBackfill();
        Map<String, String> after2 = readAll();
        assertEquals(after1, after2);
    }

    private void invokeBackfill() throws Exception {
        // 迁移只用到 dataSource，repo 依赖与 type 无关，传 null 安全
        SystemDataSeeder seeder = new SystemDataSeeder(null, null, dataSource);
        Method m = SystemDataSeeder.class.getDeclaredMethod("backfillWorkspaceType");
        m.setAccessible(true);
        m.invoke(seeder);
    }

    private void insert(String id, String type) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement("INSERT INTO workspaces (id, type) VALUES (?, ?)")) {
            ps.setString(1, id);
            if (type == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, type);
            }
            ps.executeUpdate();
        }
    }

    private Map<String, String> readAll() throws Exception {
        Map<String, String> result = new HashMap<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, type FROM workspaces")) {
            while (rs.next()) {
                result.put(rs.getString("id"), rs.getString("type"));
            }
        }
        return result;
    }
}
