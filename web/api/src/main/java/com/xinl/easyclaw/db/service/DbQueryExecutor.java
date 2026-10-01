package com.xinl.easyclaw.db.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DB 查询编排器（V30.1 收口）：db_query 的完整编排序列——只读防线 → 审计入队 →
 * 驱动层限流执行 → 脱敏渲染——<b>唯一实现</b>，供双通道（{@code DbTools} 直注册 /
 * {@code DbMcpTools} 内置 MCP）委托。此前该序列在两个通道各拷贝一份（~30 行），
 * 违背「Guard/Sanitizer/审计/渲染一律单实现双通道」的设计定稿、存在漂移风险；
 * 现在编排层与组件层一样单实现，通道只保留各自的上下文解析与权限语义差异。
 */
@Component
public class DbQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DbQueryExecutor.class);

    /** 单条查询超时（秒）：生产库重查询应快速失败，不占死连接。 */
    public static final int QUERY_TIMEOUT_SECONDS = 30;

    /** 给模型的 schema 输出上限（超长截断，提示用 table 参数缩小范围）。 */
    public static final int MAX_SCHEMA_OUTPUT_CHARS = 60_000;

    private final DbQueryGuard guard;
    private final DbResultSanitizer sanitizer;
    private final DbQueryLogReporter queryLogReporter;

    public DbQueryExecutor(DbQueryGuard guard, DbResultSanitizer sanitizer,
                           DbQueryLogReporter queryLogReporter) {
        this.guard = guard;
        this.sanitizer = sanitizer;
        this.queryLogReporter = queryLogReporter;
    }

    /**
     * db_query 编排：guard → 审计 → 执行 → 渲染。任何失败都返回可读错误文本（不抛异常），
     * 错误文案直接透传给模型帮助其自我修正。
     *
     * @param source 审计来源：{@code ai}（两通道的调用方都是 AI 回合）
     */
    public String executeQuery(DbConnectionService.DbSession session, String sql, String source) {
        // 只读防线（行为层）：首词白名单 + 危险子句定位匹配；物理防线是 DB 只读账号
        DbQueryGuard.Verdict verdict = guard.check(sql);
        if (!verdict.allowed()) {
            return "❌ " + verdict.reason();
        }
        // 审计：执行前入队（无论执行成败都记——记录的是「执行了什么」）
        queryLogReporter.enqueue(session.serverKey(), session.serverName(), session.dbType(),
                session.host(), session.database(), sql, source);
        String banner = DbSchemaRenderer.banner(session);
        try {
            return session.withConnection(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    // 行数上限下沉到驱动层：否则 SELECT 大表会把全量结果先拉进内存，
                    // sanitizer 才截断。多取 1 行用于探测「是否还有更多」，尾注据此如实
                    // 标注 ≥（见 DbResultSanitizer）。
                    st.setMaxRows(DbResultSanitizer.MAX_ROWS + 1);
                    try (ResultSet rs = st.executeQuery(sql)) {
                        return sanitizer.render(banner, rs);
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("db_query 执行失败: serverKey={}, db={}, err={}",
                    session.serverKey(), session.database(), e.getMessage());
            return banner + "\n\n❌ 执行失败: " + e.getMessage();
        }
    }

    /** db_schema 编排：渲染 + 超长截断；失败返回可读错误文本。 */
    public String renderSchema(DbConnectionService.DbSession session, String targetDb, String table) {
        try {
            String result = session.withConnection(conn ->
                    DbSchemaRenderer.renderSchema(conn, session.dbType(), targetDb, table));
            if (result.length() > MAX_SCHEMA_OUTPUT_CHARS) {
                return result.substring(0, MAX_SCHEMA_OUTPUT_CHARS)
                        + "\n...（表过多已截断，可用 table 参数缩小范围）";
            }
            return result;
        } catch (SQLException e) {
            log.warn("db_schema 失败: serverKey={}, db={}, err={}",
                    session.serverKey(), targetDb, e.getMessage());
            return "❌ 查询表结构失败: " + e.getMessage();
        }
    }
}
