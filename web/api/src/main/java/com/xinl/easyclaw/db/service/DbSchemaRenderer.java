package com.xinl.easyclaw.db.service;

import com.xinl.easyclaw.db.service.DbConnectionService.DbSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表结构渲染器（V30）：db_schema 工具的共享实现——DB 工作区直注册通道（{@code DbTools}）
 * 与内置 MCP 通道（{@code DbMcpTools}）共用同一份代码，避免两份渲染逻辑漂移
 * （设计定稿：Guard/Sanitizer/审计/渲染一律单实现双通道）。
 * <p>
 * 输出为紧凑文本（省 token）：information_schema 三库（MySQL/PG/SQLServer）通用；
 * Oracle 用 all_tables/all_tab_columns（schema 语义 = owner）。
 */
public final class DbSchemaRenderer {

    private DbSchemaRenderer() {
    }

    /**
     * 结果首行 banner（设计定稿）：{@code -- mysql 8.0.36 @ prod-mysql/orders_db（只读）}。
     * 版本字符串可能多行（Oracle），取第一行。
     */
    public static String banner(DbSession session) {
        String version = session.version() == null ? "" : session.version().split("\n")[0].trim();
        return "-- " + session.dbType() + " " + version + " @ "
                + session.serverKey() + "/" + session.database()
                + (session.readonlyHint() ? "（只读）" : "");
    }

    /** 表结构 → 紧凑文本；按库类型分发（Oracle 走 all_* 视图，其余走 information_schema）。 */
    public static String renderSchema(DbSession session, String targetDb, String table)
            throws SQLException {
        Connection conn = session.connection();
        StringBuilder sb = new StringBuilder(banner(session)).append("\n\n");
        switch (session.dbType()) {
            case "oracle" -> renderSchemaOracle(conn, sb, targetDb, table);
            default -> renderSchemaInformationSchema(conn, sb, targetDb, table);
        }
        return sb.toString();
    }

    /** MySQL/PostgreSQL/SQLServer：information_schema.tables + columns。 */
    private static void renderSchemaInformationSchema(Connection conn, StringBuilder sb, String targetDb,
                                                      String table) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY 1")) {
            ps.setString(1, targetDb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        if (tables.isEmpty()) {
            sb.append("库 ").append(targetDb).append(" 中没有可见表（0 tables）");
            return;
        }
        List<String> wanted = table == null || table.isBlank()
                ? tables : tables.stream().filter(t -> t.equalsIgnoreCase(table.trim())).toList();
        if (wanted.isEmpty()) {
            sb.append("表 ").append(table).append(" 不存在。库 ").append(targetDb)
                    .append(" 中的表（").append(tables.size()).append("）: ")
                    .append(String.join(", ", tables));
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, targetDb);
            for (String t : wanted) {
                sb.append("## ").append(t).append("\n");
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        sb.append(rs.getString(2)).append(" ")
                                .append(rs.getString(3))
                                .append("YES".equalsIgnoreCase(rs.getString(4)) ? "" : " NOT NULL")
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
    }

    /** Oracle：all_tables + all_tab_columns（schema 语义 = owner）。 */
    private static void renderSchemaOracle(Connection conn, StringBuilder sb, String targetDb,
                                           String table) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY 1")) {
            ps.setString(1, targetDb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        if (tables.isEmpty()) {
            sb.append("Schema ").append(targetDb).append(" 中没有可见表（0 tables，或当前账号无权限）");
            return;
        }
        List<String> wanted = table == null || table.isBlank()
                ? tables : tables.stream().filter(t -> t.equalsIgnoreCase(table.trim())).toList();
        if (wanted.isEmpty()) {
            sb.append("表 ").append(table).append(" 不存在。Schema ").append(targetDb)
                    .append(" 中的表（").append(tables.size()).append("）: ")
                    .append(String.join(", ", tables));
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, data_type, nullable FROM all_tab_columns "
                        + "WHERE owner = ? AND table_name = ? ORDER BY column_id")) {
            ps.setString(1, targetDb);
            for (String t : wanted) {
                sb.append("## ").append(t).append("\n");
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        sb.append(rs.getString(1)).append(" ").append(rs.getString(2))
                                .append("YES".equalsIgnoreCase(rs.getString(3)) ? "" : " NOT NULL")
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
    }
}
