package com.xinl.easyclaw.db.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryExecutor;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.type.BuiltinMcpType;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 服务 {@code easyclaw-db} 的工具面（设计定稿 §4.0.1 双消费壳之二）。
 * <p>
 * 与 DB 工作区直注册通道（{@code DbTools}）共享第二层服务核心与查询编排器
 * （{@link DbQueryExecutor}：guard/审计/执行/渲染单实现），仅适配层不同：
 * <ul>
 *   <li>MCP 通道没有 DB 页面的会话绑定 → 目标由<b>实例配置</b>锁定（见下），
 *       工具参数面只有业务参数（{@code table} / {@code sql}），
 *       {@code db_status} 只报目标库的可用性与读写性；</li>
 *   <li>权限语义差异（有意为之）：直注册通道 = ALWAYS_ASK 强制确认（生产库最高戒备）；
 *       MCP 通道 = 通用工具权限体系（场景可按需白名单化），适合信任等级较低的一般场景；</li>
 *   <li>授权闭环：连接由 hub 下发快照驱动，平台撤销授权 → DbConnectionGuard 断开连接 →
 *       工具按「连接不存在」返回可读错误。</li>
 * </ul>
 * 进程内直调（照 HTTP_TOOL 桥接模式），不起新进程；由 McpConnectionServiceImpl 在
 * transport=BUILTIN 时按 {@link #typeId()} 装配。
 * <p>
 * <b>类型化之后</b>：本类不再等于「那个叫 easyclaw-db 的内置服务」，而是 {@code db} 类型的实现——
 * 同一类型可实例化成多个服务（prod-orders / prod-report 各锁一个库）。
 * <p>
 * <b>能力的标准：只暴露能力，不暴露地址。</b>实例配置里的 {@code {serverKey, database}}
 * 在服务端解析成目标连接，<b>工具参数面与返回值里都不出现 connKey / host / port / 账号</b>：
 * 模型只需要知道「我能查这个库」。跨库的正确表达是再建一个实例，而不是让它换个地址——
 * 参数里留连接标识等于把基础设施拓扑交给模型，还顺手开出一个「诱导它查授权外目标」的越权面。
 * 目标解析三级全在服务端完成：实例配置 &gt; 会话绑定 &gt; 工作区主连接。
 * <p>
 * 老的 SYSTEM 单例没有实例配置，自动落到后两级；若后两级也定位不到，返回的是
 * 「请让人去 DB 页面选连接 / 给实例配目标库」，而不是教模型拼连接键。
 */
@Component
public class DbMcpTools implements BuiltinMcpType {

    private static final Logger log = LoggerFactory.getLogger(DbMcpTools.class);

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();

    /** 本类型标识（落 mcp_services.mcp_type） */
    static final String TYPE_ID = "db";

    private final DbConnectionService db;
    private final DbQueryExecutor queryExecutor;
    private final CloudBootstrapService cloudBootstrap;

    public DbMcpTools(DbConnectionService db, DbQueryExecutor queryExecutor,
                      CloudBootstrapService cloudBootstrap) {
        this.db = db;
        this.queryExecutor = queryExecutor;
        this.cloudBootstrap = cloudBootstrap;
    }

    // ==================== BuiltinMcpType 契约 ====================

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public String displayName() {
        return "数据库操作";
    }

    /**
     * 实例配置表单：选一个 hub 下发的数据库连接（可再指定库）。
     * <p>
     * 只描述字段与候选源，<b>不查库</b>——实际选项由 Controller 按 {@code optionsSource} 补齐。
     * 配置里<b>不存任何凭证</b>：host/port/账号/密码仍在建立物理连接时由 hub 下发快照解析。
     */
    @Override
    public List<Map<String, Object>> configSchema() {
        return List.of(
                Map.of("key", "serverKey", "label", "数据库连接 *", "type", "select",
                        "required", true, "optionsSource", "hub-db-connections",
                        "description", "平台下发的已授权数据库连接（serverKey）"),
                Map.of("key", "database", "label", "库 / schema", "type", "select",
                        "required", false, "optionsSource", "hub-db-databases",
                        "description", "缺省用该连接配置的默认库"),
                Map.of("key", "enableDdl", "label", "开放 DDL（结构变更）", "type", "switch",
                        "required", false, "default", "false",
                        "description", "默认关闭。开启后本实例才装配 db_ddl 工具"
                                + "（CREATE/ALTER/DROP/TRUNCATE 等，危险等级极高）；"
                                + "即使开放，每次执行仍需人工确认，无法被回合/永久授权跳过"));
    }

    /** 内置 MCP 工具（实例激活时装配）；目标连接由实例配置决定默认值。
     *  db_dml 为高危写操作：权限层 NON_BYPASSABLE 强制每次人工确认。
     *  db_ddl 默认<b>不装配</b>——用户在实例配置里手动「开放 DDL」后才出现，开放后仍每次确认。 */
    @Override
    public List<AgentTool> toolsFor(McpServiceEntity instance) {
        String defaultConnKey = resolveDefaultConnKey(instance);
        boolean enableDdl = resolveEnableDdl(instance);
        log.info("装配 db 内置工具面: service={}, defaultConnKey={}, ddl={}",
                instance == null ? "-" : instance.getName(),
                defaultConnKey == null ? "(未锁定，回退会话绑定)" : defaultConnKey,
                enableDdl ? "已开放" : "未开放");
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new StatusTool(defaultConnKey));
        tools.add(new SchemaTool(defaultConnKey));
        tools.add(new QueryTool(defaultConnKey));
        tools.add(new DmlTool(defaultConnKey));
        if (enableDdl) {
            tools.add(new DdlTool(defaultConnKey));
        }
        return List.copyOf(tools);
    }

    /**
     * 从实例 {@code implementationConfig} 解析默认 connKey（{@code serverKey + "/" + database}，
     * 与 {@link DbConnectionService} 注册表键同源）。
     * <p>
     * database 缺省时回退 hub 下发清单里的默认库；两者都拿不到（老 SYSTEM 单例无配置、
     * 或 cloud 未配置）返回 null，由工具在调用时回退会话绑定 / 工作区最近连接。
     */
    private String resolveDefaultConnKey(McpServiceEntity instance) {
        if (instance == null || instance.getImplementationConfig() == null
                || instance.getImplementationConfig().isBlank()) {
            return null;
        }
        String serverKey;
        String database;
        try {
            JsonNode root = CONFIG_MAPPER.readTree(instance.getImplementationConfig());
            serverKey = text(root.get("serverKey"));
            database = text(root.get("database"));
        } catch (Exception e) {
            log.warn("解析内置 db 服务配置失败 (service={}): {}", instance.getName(), e.getMessage());
            return null;
        }
        if (serverKey == null) {
            return null;
        }
        if (database == null) {
            database = cloudBootstrap.findDbConnection(serverKey)
                    .map(c -> trimToNull(c.databaseName()))
                    .orElse(null);
        }
        return database == null ? null : serverKey + "/" + database;
    }

    /**
     * 解析实例配置里的 DDL 开关（{@code enableDdl}）。默认关闭（fail-closed）：
     * 配置缺失、脏 JSON、值不是字面量 {@code true} 一律视为未开放。
     * <p>
     * DDL 是极高危操作（多数库隐式提交不可回滚），必须用户在实例上显式手动开放才装配
     * {@code db_ddl}；开放后每次执行仍走 {@code ToolPermissionPolicy.NON_BYPASSABLE}
     * 强制人工确认——「手动开放」解决的是「有没有这个工具」，「每次确认」解决的是
     * 「执行时人是否知情」，两道闸门独立生效。
     */
    private boolean resolveEnableDdl(McpServiceEntity instance) {
        if (instance == null || instance.getImplementationConfig() == null
                || instance.getImplementationConfig().isBlank()) {
            return false;
        }
        try {
            JsonNode node = CONFIG_MAPPER.readTree(instance.getImplementationConfig()).get("enableDdl");
            if (node == null || node.isNull()) {
                return false;
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            return "true".equalsIgnoreCase(node.asText().trim());
        } catch (Exception e) {
            log.warn("解析内置 db 服务 DDL 开关失败 (service={}): {}",
                    instance.getName(), e.getMessage());
            return false;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return trimToNull(node.asText());
    }

    /** 空白安全转 null：避免把空串当有效配置值 */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 目标连接解析（三级，<b>全部在服务端完成</b>）：实例锁定 &gt; 会话绑定 &gt; 工作区主连接。
     * <p>
     * <b>刻意不接受模型传入的连接标识</b>：能力的定义是「你能查库」，不是「你能连这台
     * host 上的这个库」。把 serverKey / host 之类写进参数面有三个后果——模型必须知道并复述
     * 定位信息（基础设施拓扑进了上下文）、可以被诱导去操作授权外的目标（越权面），
     * 以及连接改名后静默失效。多目标的正确表达是<b>多个实例</b>（prod-orders 一个、
     * report 一个），每个实例只认自己锁定的目标。
     * <p>
     * 实例锁定优先于会话绑定：实例配置是「这个服务被授予操作哪个库」的显式声明，
     * 会话绑定只是运行时上下文，作兜底。老 SYSTEM 单例没有实例配置，自然落到后两级。
     *
     * @return 解析出的 connKey；三级都定位不到返回 null（调用方给人看的提示，不给模型提示）
     */
    private String resolveConnKey(ToolCallParam param, String defaultConnKey, String workspaceId) {
        if (defaultConnKey != null) {
            return defaultConnKey;
        }
        String sessionId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getSessionId();
        String bound = db.connKeyForSession(sessionId, workspaceId);
        return bound != null ? bound : db.primaryConnKey(workspaceId);
    }

    /** connKey 的内部格式是 {@code serverKey/database}；对外只报库名，不报定位标识。 */
    private static String databaseOf(String connKey) {
        int slash = connKey.lastIndexOf('/');
        return slash < 0 ? connKey : connKey.substring(slash + 1);
    }

    /** 三级解析都定位不到目标时的可读提示：把定位责任交回给人，不教模型去拼连接键。 */
    private static String noTarget() {
        return "❌ 无法确定目标数据库：本服务实例未锁定目标库，当前会话也没有活跃连接。\n"
                + "定位目标不是你的职责。请让用户二选一：在 DB 页面选择连接并点「连接」，"
                + "或在 MCP 页面为本服务实例配置目标库。";
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
        private final String defaultConnKey;

        StatusTool(String defaultConnKey) {
            this.defaultConnKey = defaultConnKey;
        }

        @Override public String getName() { return "db_status"; }
        @Override public String getDescription() {
            return "报告本服务当前锁定的目标库及其可用性：库名、数据库类型、版本、是否只读。\n"
                    + "【何时用】动手查数据前确认目标库在线、以及它是只读还是可写。\n"
                    + "本工具刻意不返回主机、端口、账号或连接键——那些属于平台装配信息，"
                    + "不属于你的能力面，也不需要你复述。";
        }
        @Override public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public boolean isReadOnly() { return true; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                List<DbConnectionService.DbConnectionStatus> rows = db.status(wid);
                if (rows.isEmpty()) {
                    return text("当前没有活跃数据库连接。连接由平台下发授权驱动，"
                            + "请让用户在 DB 页面选择连接与库并点击「连接」。");
                }
                // 只报「能力面」：目标库 + 类型 + 版本 + 读写性。host/port/账号/connKey 一律不回——
                // 回给模型等于把基础设施拓扑塞进上下文，还会诱导它去操作授权外的目标。
                StringBuilder sb = new StringBuilder();
                if (defaultConnKey != null) {
                    sb.append("本服务锁定目标库：").append(databaseOf(defaultConnKey)).append('\n');
                }
                sb.append("当前工作区活跃连接 ").append(rows.size()).append(" 个：\n");
                for (DbConnectionService.DbConnectionStatus row : rows) {
                    sb.append("- ").append(row.database())
                            .append("  [").append(row.dbType()).append(' ')
                            .append(row.version() == null ? "-" : row.version()).append(']')
                            .append(row.readonlyHint() ? " 只读" : " 可写")
                            .append(row.alive() ? "" : "（已断开）")
                            .append('\n');
                }
                return text(sb.toString());
            }).onErrorResume(ex -> {
                log.error("db_status(MCP) 失败", ex);
                return Mono.just(error("db_status 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_schema ====================

    private class SchemaTool implements AgentTool {
        private final String defaultConnKey;

        SchemaTool(String defaultConnKey) {
            this.defaultConnKey = defaultConnKey;
        }

        @Override public String getName() { return "db_schema"; }
        @Override public String getDescription() {
            return "查看本服务目标库的表结构：表清单与各表列名/类型（紧凑文本）。\n"
                    + "【何时用】写查询前先了解表结构。目标库由平台装配决定，无需也无法在此指定；"
                    + "要看另一个库的结构，请让平台为那个库另建一个能力实例。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("table", Map.of("type", "string", "description", "只看某张表的列（缺省 = 列出全部表名）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            // 参数面只留业务参数：连接标识（connKey）与库名（database）都已移除。
            // 目标由实例锁定，跨库的正确表达是「再建一个实例」，而不是让模型换地址。
            schema.put("required", List.of());
            return schema;
        }
        @Override public boolean isReadOnly() { return true; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String table = str(param.getInput().get("table"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                String connKey = resolveConnKey(param, defaultConnKey, wid);
                if (connKey == null) {
                    return text(noTarget());
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    // 不回 connKey（那是定位标识），只报库名：够模型判断「目标掉线」这件事
                    return text("❌ 目标库连接不存在或已断开：" + databaseOf(connKey)
                            + "。请让用户在 DB 页面重新连接。");
                }
                return text(queryExecutor.renderSchema(session, session.database(), table));
            }).onErrorResume(ex -> {
                log.error("db_schema(MCP) 异常", ex);
                return Mono.just(error("db_schema 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_query ====================

    private class QueryTool implements AgentTool {
        private final String defaultConnKey;

        QueryTool(String defaultConnKey) {
            this.defaultConnKey = defaultConnKey;
        }

        @Override public String getName() { return "db_query"; }
        @Override public String getDescription() {
            return "在本服务的目标库上执行一条只读 SQL 查询（SELECT/WITH），返回 Markdown 表格（敏感列已脱敏，超长已截断）。\n"
                    + "【何时用】查数据、统计、核对业务问题。目标库由平台装配决定，无需也无法在参数里指定。\n"
                    + "【边界】仅允许只读查询；写操作（INSERT/UPDATE/DELETE/DDL）会被拒绝。结果最多 500 行，空结果明确标注 0 rows。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("sql", Map.of("type", "string", "description", "要执行的只读 SQL（单条 SELECT/WITH 语句）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            // 只有 sql：能力即「查这个库」，连接标识不进参数面（与 SchemaTool 同一口径）
            schema.put("required", List.of("sql"));
            return schema;
        }
        @Override public boolean isReadOnly() { return false; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String sql = str(param.getInput().get("sql"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                if (sql == null || sql.isBlank()) {
                    return text("❌ sql 不能为空。");
                }
                String connKey = resolveConnKey(param, defaultConnKey, wid);
                if (connKey == null) {
                    return text(noTarget());
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    return text("❌ 目标库连接不存在或已断开：" + databaseOf(connKey)
                            + "。请让用户在 DB 页面重新连接。");
                }
                // 编排（guard/审计/执行/渲染）与直注册通道同一份 DbQueryExecutor
                return text(queryExecutor.executeQuery(session, sql, "ai"));
            }).onErrorResume(ex -> {
                log.error("db_query(MCP) 异常", ex);
                return Mono.just(error("db_query 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_dml ====================

    /**
     * 数据变更工具（INSERT/UPDATE/DELETE/MERGE）。危险等级「高」：
     * 权限层挂在 {@code ToolPermissionPolicy.NON_BYPASSABLE}——用户即使点了「永久允许」，
     * 每次调用仍会弹人工确认；本类描述里如实告知模型这一约束，让它提前向用户说明。
     */
    private class DmlTool implements AgentTool {
        private final String defaultConnKey;

        DmlTool(String defaultConnKey) {
            this.defaultConnKey = defaultConnKey;
        }

        @Override public String getName() { return "db_dml"; }
        @Override public String getDescription() {
            return "在本服务的目标库上执行一条数据变更语句（INSERT/UPDATE/DELETE/MERGE），返回影响行数。\n"
                    + "【危险等级】⚠️ 高：直接修改生产数据。每次执行都会弹出人工确认，且该确认无法被永久授权跳过——"
                    + "请在调用前向用户说明将要执行的语句与预期影响范围。\n"
                    + "【何时用】用户明确要求修改数据时。先 db_query 核对目标行，再提交变更，执行后如实转述影响行数。\n"
                    + "【边界】仅允许单条 DML 语句；查询用 db_query，结构变更用 db_ddl。目标库由平台装配决定，无法在参数里指定。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("sql", Map.of("type", "string",
                    "description", "要执行的单条 DML 语句（INSERT/UPDATE/DELETE/MERGE）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("sql"));
            return schema;
        }
        @Override public boolean isReadOnly() { return false; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String sql = str(param.getInput().get("sql"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                if (sql == null || sql.isBlank()) {
                    return text("❌ sql 不能为空。");
                }
                String connKey = resolveConnKey(param, defaultConnKey, wid);
                if (connKey == null) {
                    return text(noTarget());
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    return text("❌ 目标库连接不存在或已断开：" + databaseOf(connKey)
                            + "。请让用户在 DB 页面重新连接。");
                }
                // 编排（写语句校验/审计/执行/渲染）与查询同一份 DbQueryExecutor
                return text(queryExecutor.executeWrite(session, sql, "DML", "ai"));
            }).onErrorResume(ex -> {
                log.error("db_dml(MCP) 异常", ex);
                return Mono.just(error("db_dml 失败: " + ex.getMessage()));
            });
        }
    }

    // ==================== db_ddl ====================

    /**
     * 结构变更工具（CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT）。危险等级「极高」：
     * 多数数据库的 DDL 隐式提交、不可回滚，DROP/TRUNCATE 直接丢数据。
     * 与 {@link DmlTool} 同样挂在 NON_BYPASSABLE——每次执行强制人工确认。
     */
    private class DdlTool implements AgentTool {
        private final String defaultConnKey;

        DdlTool(String defaultConnKey) {
            this.defaultConnKey = defaultConnKey;
        }

        @Override public String getName() { return "db_ddl"; }
        @Override public String getDescription() {
            return "在本服务的目标库上执行一条结构变更语句（CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT）。\n"
                    + "【危险等级】🔴 极高：修改数据库结构，多数数据库隐式提交、不可回滚，DROP/TRUNCATE 会直接丢失数据。"
                    + "每次执行都会弹出人工确认，且该确认无法被永久授权跳过——请在调用前向用户完整说明语句内容与后果。\n"
                    + "【何时用】用户明确要求变更表结构/索引等数据库对象时。能不用就不用；能用 ALTER ADD COLUMN 完成的不要 DROP 重建。\n"
                    + "【边界】仅允许单条 DDL 语句；数据变更用 db_dml，查询用 db_query。目标库由平台装配决定，无法在参数里指定。";
        }
        @Override public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("sql", Map.of("type", "string",
                    "description", "要执行的单条 DDL 语句（CREATE/ALTER/DROP/TRUNCATE/RENAME/COMMENT）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("sql"));
            return schema;
        }
        @Override public boolean isReadOnly() { return false; }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String wid = workspaceId(param);
                String sql = str(param.getInput().get("sql"));
                if (wid == null) {
                    return text("❌ 无法确定工作区上下文。");
                }
                if (sql == null || sql.isBlank()) {
                    return text("❌ sql 不能为空。");
                }
                String connKey = resolveConnKey(param, defaultConnKey, wid);
                if (connKey == null) {
                    return text(noTarget());
                }
                DbConnectionService.DbSession session = db.session(wid, connKey);
                if (session == null) {
                    return text("❌ 目标库连接不存在或已断开：" + databaseOf(connKey)
                            + "。请让用户在 DB 页面重新连接。");
                }
                return text(queryExecutor.executeWrite(session, sql, "DDL", "ai"));
            }).onErrorResume(ex -> {
                log.error("db_ddl(MCP) 异常", ex);
                return Mono.just(error("db_ddl 失败: " + ex.getMessage()));
            });
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
