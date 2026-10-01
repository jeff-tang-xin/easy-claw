package com.xinl.easyclaw.db.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表结构渲染器（V30）：db_schema 工具的共享实现——DB 工作区直注册通道与内置 MCP 通道
 * 共用同一份代码（经 {@code DbQueryExecutor} 编排），避免两份渲染逻辑漂移。
 * <p>
 * 输出为紧凑文本（省 token）。MySQL/PostgreSQL/SQLServer 走 information_schema、
 * Oracle 走 all_* 视图（schema 语义 = owner）——两类视图的查询形状一致，仅 SQL 文本、
 * 列序与文案名词不同，统一由 {@link SchemaDialect} 参数化，消除双份拷贝。
 * <p>
 * V30.1 修复：Oracle {@code all_tab_columns.nullable} 取值是 {@code Y/N} 而非
 * {@code YES/NO}，旧实现按 YES 判等导致 Oracle 所有列都被误标 NOT NULL；
 * 现按 Y/YES 双口径归一。
 */
public final class DbSchemaRenderer {

    private DbSchemaRenderer() {
    }

    /**
     * 方言参数：表清单 SQL（单参 schema/owner）、列清单 SQL（双参 schema/owner + table）、
     * 列结果集中 列名/类型/可空 的下标（1-based）、空清单文案名词（库 / Schema）。
     */
    private record SchemaDialect(String tablesSql, String columnsSql,
                                 int nameIdx, int typeIdx, int nullableIdx, String scopeNoun) {
    }

    private static final SchemaDialect INFORMATION_SCHEMA = new SchemaDialect(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY 1",
            "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
            2, 3, 4, "库");

    private static final SchemaDialect ORACLE = new SchemaDialect(
            "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY 1",
            "SELECT column_name, data_type, nullable FROM all_tab_columns "
                    + "WHERE owner = ? AND table_name = ? ORDER BY column_id",
            1, 2, 3, "Schema");

    /**
     * 结果首行 banner（设计定稿）：{@code -- mysql 8.0.36 @ prod-mysql/orders_db（只读）}。
     * 版本字符串可能多行（Oracle），取第一行。
     */
    public static String banner(DbConnectionService.DbSession session) {
        String version = session.version() == null ? "" : session.version().split("\n")[0].trim();
        return "-- " + session.dbType() + " " + version + " @ "
                + session.serverKey() + "/" + session.database()
                + (session.readonlyHint() ? "（只读）" : "");
    }

    /** 表结构 → 紧凑文本；按库类型选方言（Oracle 走 all_* 视图，其余走 information_schema）。 */
    public static String renderSchema(Connection conn, String dbType, String targetDb, String table)
            throws SQLException {
        SchemaDialect dialect = "oracle".equals(dbType) ? ORACLE : INFORMATION_SCHEMA;
        StringBuilder sb = new StringBuilder(bannerOf(conn, dbType, targetDb)).append("\n\n");
        List<String> tables = listTables(conn, dialect, targetDb);
        if (tables.isEmpty()) {
            sb.append(dialect.scopeNoun()).append(" ").append(targetDb)
                    .append(" 中没有可见表（0 tables，或当前账号无权限）");
            return sb.toString();
        }
        List<String> wanted = table == null || table.isBlank()
                ? tables : tables.stream().filter(t -> t.equalsIgnoreCase(table.trim())).toList();
        if (wanted.isEmpty()) {
            sb.append("表 ").append(table).append(" 不存在。").append(dialect.scopeNoun())
                    .append(" ").append(targetDb).append(" 中的表（").append(tables.size())
                    .append("）: ").append(String.join(", ", tables));
            return sb.toString();
        }
        try (PreparedStatement ps = conn.prepareStatement(dialect.columnsSql())) {
            ps.setString(1, targetDb);
            for (String t : wanted) {
                sb.append("## ").append(t).append("\n");
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        sb.append(rs.getString(dialect.nameIdx())).append(" ")
                                .append(rs.getString(dialect.typeIdx()))
                                .append(isNullable(rs.getString(dialect.nullableIdx())) ? "" : " NOT NULL")
                                .append("\n");
                    }
                    if (!any) {
                        sb.append("(无列信息)\n");
                    }
                }
                sb.append("\n");
            }
        }
        if (table == null || table.isBlank()) {
            sb.append("(共 ").append(tables.size()).append(" 张表，以上为全部表结构)\n");
        }
        return sb.toString();
    }

    /** 可空标记归一：information_schema 用 YES/NO，Oracle 用 Y/N。 */
    private static boolean isNullable(String raw) {
        return "Y".equalsIgnoreCase(raw) || "YES".equalsIgnoreCase(raw);
    }

    private static List<String> listTables(Connection conn, SchemaDialect dialect, String targetDb)
            throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(dialect.tablesSql())) {
            ps.setString(1, targetDb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    /**
     * 渲染路径的首行 banner：调用方（DbQueryExecutor）仅持 Connection 时从 JDBC 元数据
     * 现取（与 {@link #banner} 同构；只读提示标记在会话快照上，此处不重复）。
     */
    private static String bannerOf(Connection conn, String dbType, String database) throws SQLException {
        String version = String.valueOf(conn.getMetaData().getDatabaseProductVersion());
        return "-- " + dbType + " " + version.split("\n")[0].trim() + " @ " + database;
    }
}
