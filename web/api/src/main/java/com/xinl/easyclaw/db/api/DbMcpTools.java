package com.xinl.easyclaw.db.api;

import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryGuard;
import com.xinl.easyclaw.db.service.DbQueryLogReporter;
import com.xinl.easyclaw.db.service.DbResultSanitizer;
import com.xinl.easyclaw.db.service.DbSchemaRenderer;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 服务 {@code easyclaw-db} 的工具面（设计定稿 §4.0.1 双消费壳之二）。
 * <p>
 * 与 DB 工作区直注册通道（{@code DbTools}）共享第二层服务核心
 * （DbConnectionService / DbQueryGuard / DbResultSanitizer / 审计上报 / DbSchemaRenderer），
 * 仅适配层不同：
 * <ul>
 *   <li>MCP 通道没有 DB 页面的会话绑定 → {@code db_schema}/{@code db_query} 带
 *       {@code connKey} 参数（{@code serverKey/database}，与 DbConnectionService 注册表键一致），
 *       {@code db_status} 列出本工作区全部活跃连接；</li>
 *   <li>权限语义差异（有意为之）：直注册通道 = ALWAYS_ASK 强制确认（生产库最高戒备）；
 *       MCP 通道 = 通用工具权限体系（场景可按需白名单化），适合信任等级较低的一般场景；</li>
 *   <li>授权闭环：连接由 hub 下发快照驱动，平台撤销授权 → DbConnectionGuard 断开连接 →
 *       工具按「连接不存在」返回可读错误。</li>
 * </ul>
 * 进程内直调（照 HTTP_TOOL 桥接模式），不起新进程；由 McpConnectionServiceImpl 在
 * connect("easyclaw-db") 时装配。
 */
@Component
public class DbMcpTools {

    private static final Logger log = LoggerFactory.getLogger(DbMcpTools.class);
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final DbConnectionService db;
    private final DbQueryGuard guard;
    private final DbResultSanitizer sanitizer;
    private final DbQueryLogReporter queryLogReporter;

    public DbMcpTools(DbConnectionService db, DbQueryGuard guard, DbResultSanitizer sanitizer,
                      DbQueryLogReporter queryLogReporter) {
        this.db = db;
        this.guard = guard;
        this.sanitizer = sanitizer;
        this.queryLogReporter = queryLogReporter;
    }

    /** 三个内置 MCP 工具（McpConnectionServiceImpl connect 时装配） */
    public List<AgentTool> tools() {
        return List.of(new StatusTool(), new SchemaTool(), new QueryTool());
    }

    // ==================== 上下文解析 ====================

    /** MCP 工具调用同样经 RuntimeContext 携带 WorkspaceContext（buildContext 注入） */
    private static WorkspaceContext workspace(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            return null;
        }
        return param.getRuntimeContext().get(WorkspaceContext.class);
    }

    private static String workspaceId(ToolCallParam param) {
        WorkspaceContext ws = workspace(param);
        return ws == null ? null : ws.getWorkspaceId();
    }

    private static ToolResultBlock text(String s) {
        return ToolResultBlock.text(s);
    }

    private static ToolResultBlock error(String s) {
        return ToolResultBlock.error(s);
    }

    // ==================== db_status ====================

    private class StatusTool implements AgentTool {
        @Override public String getName() { return "db_status"; }
        @Override public String getDescription() {
            return "列出当前工作区全部活跃数据库连接：connKey、数据库类型、版本、连接的库、只读提示。\n"
                    + "【何时用】开始工作前先看有哪些可用连接；db_schema/db_query 的 connKey 参数取自这里的 connKey 值。";
        }
        @Override public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public boolean isReadOnly() { return true; }
        @Override public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return reactor.core.publisher.Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                List<Map<String, Object>> rows = db.status(wid);
                if (rows.isEmpty()) {
                    return text("当前没有活跃数据库连接。连接由平台下发授权驱动，"
                            + "请让用户在 DB 页面选择连接与库并点击「连接」。");
                }
                StringBuilder sb = new StringBuilder("活跃数据库连接（").append(rows.size()).append("）:\n");
                for (Map<String, Object> row : rows) {
                    sb.append("- ").append(row.get("connKey"))
                            .append("  [").append(row.get("dbType")).append("]")
                            .append(" 库=").append(row.get("database"))
                            .append(" 版本=").append(row.get("version"))
                            .append((Boolean.TRUE.equals(row.get("readonlyHint"))) ? "（只读）" : "")
                            .append("\n");
                }
                return text(sb.toString());
            }).onErrorResume(ex -> {
                log.error("db_status(MCP) 失败", ex);
                return reactor.core.publisher.Mono.just(error("db_status 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_schema ====================

    private class SchemaTool implements AgentTool {
        @Override public String getName() { return "db_schema"; }
        @Override public String getDescription() {
            return "查看数据库的表结构：表清单与各表列名/类型（紧凑文本）。\n"
                    + "【何时用】写查询前先了解表结构。connKey 必填（db_status 返回的 connKey，形如 prod-mysql/orders_db）；"
                    + "database 可看同一连接实例上其他库的结构。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("connKey", Map.of("type", "string", "description", "连接键（db_status 返回的 connKey，形如 serverKey/database）"));
            props.put("database", Map.of("type", "string", "description", "要查看的库名（缺省 = connKey 中的库）"));
            props.put("table", Map.of("type", "string", "description", "只看某张表的列（缺省 = 列出全部表名）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("connKey"));
            return schema;
        }
        @Override public boolean isReadOnly() { return true; }
        @Override public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return reactor.core.publisher.Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String connKey = str(param.getInput().get("connKey"));
                String database = str(param.getInput().get("database"));
                String table = str(param.getInput().get("table"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                if (connKey == null) {
                    return text("❌ connKey 不能为空：先调 db_status 获取可用连接。");
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    return text("❌ 连接不存在或已断开: " + connKey + "。先调 db_status 查看可用连接。");
                }
                String targetDb = database == null || database.isBlank() ? session.database() : database.trim();
                try {
                    String result = DbSchemaRenderer.renderSchema(session, targetDb, table);
                    if (result.length() > MAX_OUTPUT_CHARS) {
                        result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...（表过多已截断，可用 table 参数缩小范围）";
                    }
                    return text(result);
                } catch (SQLException e) {
                    log.warn("db_schema(MCP) 失败: workspace={}, connKey={}, err={}", wid, connKey, e.getMessage());
                    return text("❌ 查询表结构失败: " + e.getMessage());
                }
            }).onErrorResume(ex -> {
                log.error("db_schema(MCP) 异常", ex);
                return reactor.core.publisher.Mono.just(error("db_schema 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_query ====================

    private class QueryTool implements AgentTool {
        @Override public String getName() { return "db_query"; }
        @Override public String getDescription() {
            return "在指定连接的库上执行一条只读 SQL 查询（SELECT/WITH），返回 Markdown 表格（敏感列已脱敏，超长已截断）。\n"
                    + "【何时用】查数据、统计、核对业务问题。connKey 必填（db_status 返回的 connKey）。\n"
                    + "【边界】仅允许只读查询；写操作（INSERT/UPDATE/DELETE/DDL）会被拒绝。结果最多 500 行，空结果明确标注 0 rows。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("connKey", Map.of("type", "string", "description", "连接键（db_status 返回的 connKey，形如 serverKey/database）"));
            props.put("sql", Map.of("type", "string", "description", "要执行的只读 SQL（单条 SELECT/WITH 语句）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("connKey", "sql"));
            return schema;
        }
        @Override public boolean isReadOnly() { return false; }
        @Override public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return reactor.core.publisher.Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String connKey = str(param.getInput().get("connKey"));
                String sql = str(param.getInput().get("sql"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                if (connKey == null) {
                    return text("❌ connKey 不能为空：先调 db_status 获取可用连接。");
                }
                if (sql == null || sql.isBlank()) {
                    return text("❌ sql 不能为空。");
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    return text("❌ 连接不存在或已断开: " + connKey + "。先调 db_status 查看可用连接。");
                }
                // 只读防线（行为层）与审计：与直注册通道同一份 Guard / Reporter
                DbQueryGuard.Verdict verdict = guard.check(sql);
                if (!verdict.allowed()) {
                    return text("❌ " + verdict.reason());
                }
                queryLogReporter.enqueue(session.serverKey(), session.serverName(), session.dbType(),
                        session.host(), session.database(), sql, "ai");
                String banner = DbSchemaRenderer.banner(session);
                try (Statement st = session.connection().createStatement()) {
                    st.setQueryTimeout(30);
                    // 与直注册通道同一策略：行数上限下沉到驱动层，多取 1 行用于探测是否还有更多
                    st.setMaxRows(DbResultSanitizer.MAX_ROWS + 1);
                    try (var rs = st.executeQuery(sql)) {
                        String rendered = sanitizer.render(banner, rs);
                        log.info("db_query(MCP) 执行完成: workspace={}, connKey={}", wid, connKey);
                        return text(rendered);
                    }
                } catch (SQLException e) {
                    log.warn("db_query(MCP) 执行失败: workspace={}, sql={}, err={}", wid, sql, e.getMessage());
                    return text(banner + "\n\n❌ 执行失败: " + e.getMessage());
                }
            }).onErrorResume(ex -> {
                log.error("db_query(MCP) 异常", ex);
                return reactor.core.publisher.Mono.just(error("db_query 失败: " + ex.getMessage()));
            });
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
