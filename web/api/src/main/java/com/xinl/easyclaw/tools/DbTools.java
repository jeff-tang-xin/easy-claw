package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryGuard;
import com.xinl.easyclaw.db.service.DbQueryLogReporter;
import com.xinl.easyclaw.db.service.DbResultSanitizer;
import com.xinl.easyclaw.db.service.DbSchemaRenderer;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库场景工具（仅在激活「DB 工作区」场景的工作区装配，见 WorkspaceAgentBuilder）。
 * <p>
 * 三个工具（设计定稿）：
 * <ul>
 *   <li>{@code db_status}：当前连接的数据库类型/版本/只读状态；</li>
 *   <li>{@code db_schema}：看表结构——保留 database 参数（看别的库结构合理）；</li>
 *   <li>{@code db_query}：执行只读查询——<b>无 database 参数</b>，作用域锁定会话绑定库，
 *       跨库 JOIN 用 SQL 限定名。</li>
 * </ul>
 * 权限语义：三个工具全部 ALWAYS_ASK——每次调用都弹用户确认，用户点「本轮总是允许/
 * 永久允许」也无法让它静音（生产库受控入口，见 ToolPermissionPolicy 与
 * WorkspaceAgentBuilder.buildPermissionContext）。
 * <p>
 * 呈现双通道（设计定稿）：给 AI 的查询结果经 {@link DbResultSanitizer} 脱敏 + 三重截断；
 * 用户在确认弹窗看真实 SQL、在聊天流看渲染后的 Markdown。
 */
@Component
public class DbTools {

    private static final Logger log = LoggerFactory.getLogger(DbTools.class);
    /** 返回给模型的输出上限：超长输出截断，防止吃满上下文 */
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final DbConnectionService db;
    private final DbQueryGuard guard;
    private final DbResultSanitizer sanitizer;
    private final DbQueryLogReporter queryLogReporter;

    public DbTools(DbConnectionService db, DbQueryGuard guard, DbResultSanitizer sanitizer,
                   DbQueryLogReporter queryLogReporter) {
        this.db = db;
        this.guard = guard;
        this.sanitizer = sanitizer;
        this.queryLogReporter = queryLogReporter;
    }

    // ==================== db_status ====================

    @Tool(name = "db_status", description = "查看当前会话绑定数据库连接的状态：数据库类型、版本、连接的库、只读提示。\n"
            + "【何时用】开始工作前先了解连的是什么库、什么版本（影响可用的 SQL 语法）；或用户问「连的哪个库」。\n"
            + "【前置】用户必须先在 DB 页面建立连接；未连接时返回错误，此时提示用户先连接，不要重试。")
    public String dbStatus(WorkspaceContext workspace, RuntimeContext rc) {
        DbConnectionService.DbSession session = resolveSession(workspace, rc);
        if (session == null) {
            return notConnected();
        }
        return banner(session) + "\n状态: 已连接"
                + "\n只读提示: " + (session.readonlyHint() ? "是（连接配置标记为只读，请只执行查询）" : "未标记（仍请只执行查询，写操作由数据库账号权限兜底）");
    }

    // ==================== db_schema ====================

    @Tool(name = "db_schema", description = "查看数据库的表结构：表清单与各表列名/类型（紧凑文本）。\n"
            + "【何时用】写查询前先了解表结构；参数 database 可看同一连接实例上其他库的结构（当前查询仍锁定在本会话的库）。\n"
            + "【前置】用户必须先在 DB 页面建立连接。")
    public String dbSchema(
            @ToolParam(name = "database", required = false,
                    description = "要查看的库名（缺省 = 当前会话绑定的库）") String database,
            @ToolParam(name = "table", required = false,
                    description = "只看某张表的列（缺省 = 列出全部表名）") String table,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        DbConnectionService.DbSession session = resolveSession(workspace, rc);
        if (session == null) {
            return notConnected();
        }
        String targetDb = database == null || database.isBlank() ? session.database() : database.trim();
        try {
            String result = DbSchemaRenderer.renderSchema(session, targetDb, table);
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...（表过多已截断，可用 table 参数缩小范围）";
            }
            return result;
        } catch (SQLException e) {
            log.warn("db_schema 失败: workspace={}, db={}, err={}",
                    workspace == null ? null : workspace.getWorkspaceId(), targetDb, e.getMessage());
            return "❌ 查询表结构失败: " + e.getMessage();
        }
    }

    // ==================== db_query ====================

    @Tool(name = "db_query", description = "在当前会话绑定的库上执行一条只读 SQL 查询（SELECT/WITH），返回 Markdown 表格（敏感列已脱敏，超长已截断）。\n"
            + "【何时用】查数据、统计、核对业务问题。作用域锁定本会话的库——跨库查询用 SQL 限定名（如 other_db.table）。\n"
            + "【边界】仅允许只读查询；写操作（INSERT/UPDATE/DELETE/DDL）会被拒绝。每次调用都会向用户弹确认（无法绕过）。\n"
            + "【注意】结果最多 500 行，需要聚合分析请写好 SQL；空结果会明确标注 0 rows。")
    public String dbQuery(
            @ToolParam(name = "sql", description = "要执行的只读 SQL（单条 SELECT/WITH 语句）") String sql,
            WorkspaceContext workspace,
            RuntimeContext rc) {
        if (sql == null || sql.isBlank()) {
            return "❌ sql 不能为空。";
        }
        DbConnectionService.DbSession session = resolveSession(workspace, rc);
        if (session == null) {
            return notConnected();
        }
        // 只读防线（行为层）：首词白名单 + 危险子句定位匹配；物理防线是 DB 只读账号
        DbQueryGuard.Verdict verdict = guard.check(sql);
        if (!verdict.allowed()) {
            return "❌ " + verdict.reason();
        }
        // 审计：执行前入队（无论执行成败都记——记录的是「执行了什么」）
        queryLogReporter.enqueue(session.serverKey(), session.serverName(), session.dbType(),
                session.host(), session.database(), sql, "ai");
        String banner = banner(session);
        try (Statement st = session.connection().createStatement()) {
            st.setQueryTimeout(30);
            // 行数上限下沉到驱动层：否则 SELECT 大表会把全量结果先拉进内存，sanitizer 才截断。
            // 多取 1 行用于探测「是否还有更多」，尾注据此如实标注 ≥（见 DbResultSanitizer）。
            st.setMaxRows(DbResultSanitizer.MAX_ROWS + 1);
            try (ResultSet rs = st.executeQuery(sql)) {
                String rendered = sanitizer.render(banner, rs);
                log.info("db_query 执行完成: workspace={}, serverKey={}, db={}",
                        workspace == null ? null : workspace.getWorkspaceId(),
                        session.serverKey(), session.database());
                return rendered;
            }
        } catch (SQLException e) {
            log.warn("db_query 执行失败: workspace={}, sql={}, err={}",
                    workspace == null ? null : workspace.getWorkspaceId(), sql, e.getMessage());
            return banner + "\n\n❌ 执行失败: " + e.getMessage();
        }
    }

    // ==================== 会话解析与公共片段 ====================

    /** 解析目标会话：会话绑定优先，未绑定回退工作区最近建立的连接。 */
    private DbConnectionService.DbSession resolveSession(WorkspaceContext workspace, RuntimeContext rc) {
        if (workspace == null || workspace.getWorkspaceId() == null) {
            return null;
        }
        String workspaceId = workspace.getWorkspaceId();
        String bound = db.connKeyForSession(rc == null ? null : rc.getSessionId(), workspaceId);
        String connKey = bound != null ? bound : db.primaryConnKey(workspaceId);
        return connKey == null ? null : db.session(workspaceId, connKey);
    }

    private static String notConnected() {
        return "❌ 尚未连接数据库：请让用户先在 DB 页面选择连接与库并点击「连接」，再重试。";
    }

    /** banner 委托共享渲染器（与 MCP 通道同一份实现） */
    private static String banner(DbConnectionService.DbSession session) {
        return DbSchemaRenderer.banner(session);
    }
}
