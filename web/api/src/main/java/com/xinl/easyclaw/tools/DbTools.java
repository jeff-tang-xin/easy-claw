package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryExecutor;
import com.xinl.easyclaw.db.service.DbSchemaRenderer;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

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
 * 权限语义（V31）：三工具静默放行（ToolPermissionPolicy SILENTLY_ALLOWED）——
 * db_status/db_schema 是纯元数据读取；db_query 有 {@link DbQueryGuard} 只读防线
 * （首词白名单 + 危险子句定位匹配），物理防线是 DB 只读账号，行为层不再逐次弹确认。
 * <p>
 * 呈现双通道（设计定稿）：给 AI 的查询结果经 {@link DbResultSanitizer} 脱敏 + 三重截断；
 * 用户在确认弹窗看真实 SQL、在聊天流看渲染后的 Markdown。
 * <p>
 * V30.1：查询/表结构的编排序列（guard → 审计 → 执行 → 渲染）收口到
 * {@link DbQueryExecutor} 单实现，本类只保留上下文解析与工具声明——与内置 MCP 通道
 * （{@code DbMcpTools}）共享同一份编排，杜绝双份拷贝漂移。
 */
@Component
public class DbTools {

    private final DbConnectionService db;
    private final DbQueryExecutor queryExecutor;

    public DbTools(DbConnectionService db, DbQueryExecutor queryExecutor) {
        this.db = db;
        this.queryExecutor = queryExecutor;
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
        return DbSchemaRenderer.banner(session) + "\n状态: 已连接"
                + "\n只读提示: " + (session.readonlyHint() ? "是（连接配置标记为只读，请只执行查询）" : "未标记（仍请只执行查询，写操作由数据库账号权限兜底）");
    }

    // ==================== db_schema ====================

    @Tool(name = "db_schema", description = "查看数据库的表结构：表清单与各表列名/类型（紧凑文本）。\n"
            + "【何时用】写查询前先了解表结构；表清单返回 schema.table 复合名（如 hub.organizations），"
            + "查询时直接用作 SQL 限定名。参数 table 可只看某张表的列（支持复合名或裸名）。\n"
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
        return queryExecutor.renderSchema(session, targetDb, table);
    }

    // ==================== db_query ====================

    @Tool(name = "db_query", description = "在当前会话绑定的库上执行一条只读 SQL 查询（SELECT/WITH），返回 Markdown 表格（敏感列已脱敏，超长已截断）。\n"
            + "【何时用】查数据、统计、核对业务问题。作用域锁定本会话的库——跨 schema 查询用 SQL 限定名（如 hub.table）。\n"
            + "【边界】仅允许只读查询；写操作（INSERT/UPDATE/DELETE/DDL）会被只读防线拒绝，数据库账号本身也是只读。\n"
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
        return queryExecutor.executeQuery(session, sql, "ai");
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
}
