package com.xinl.easyclaw.db.service;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeDbConnectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * spoke 端数据库连接服务（V30）：管理 (workspaceId, serverKey, database) → JDBC 物理连接。
 * <p>
 * 核心口径（设计定稿）：<b>每个 (serverKey, database) 组合 = 独立物理连接</b>——
 * 规避 setCatalog/SET search_path 这类连接级状态被多会话污染；同一 (serverKey, database)
 * 在同一工作区内复用已有连接。连接配置只来自 hub 下发快照（{@link CloudBootstrapService}），
 * spoke 不持久化任何连接配置或凭证。
 * <p>
 * 版本探测统一走 {@link java.sql.DatabaseMetaData}（四库一致，不写方言 SQL）。
 * schema 初始化：Oracle 建连后执行 {@code ALTER SESSION SET CURRENT_SCHEMA}、
 * PostgreSQL 执行 {@code SET search_path}（各一次）；MySQL/SQLServer 库名走连接 URL。
 */
@Service
public class DbConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionService.class);

    /** 单工作区最大活跃连接数（防泄漏；DB 工作区场景实际只会开个位数） */
    private static final int MAX_CONNECTIONS_PER_WORKSPACE = 16;

    /** JDBC 登录超时（秒）：生产库连不上应快速失败 */
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    /** TCP 建连超时（秒）：MySQL URL 按毫秒、PG 按秒消费同一常量，避免双处定义漂移 */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    /** 套接字读超时（秒）：查询超过该时长视为连接异常 */
    private static final int SOCKET_TIMEOUT_SECONDS = 30;

    /** 活跃连接注册表：workspaceId → (connKey=serverKey + "/" + database → 会话) */
    private final Map<String, Map<String, DbSession>> sessions = new ConcurrentHashMap<>();

    /** 会话绑定：sessionId → workspaceId|connKey（DB 工作区每库一个会话，AI 固定作用于绑定库） */
    private final Map<String, String> sessionConn = new ConcurrentHashMap<>();

    private final CloudBootstrapService cloudBootstrap;

    /**
     * 传输加密开关（默认 false = 现状内网明文）：开启后 MySQL useSSL=true（并关闭
     * allowPublicKeyRetrieval）、SQLServer encrypt=true。跨不可信网络访问生产库必须开启；
     * PG 驱动默认与 服务端协商 SSL、Oracle 走 TCPS 与否由服务端配置决定，此处不干预。
     */
    @Value("${easyclaw.db.ssl:false}")
    private boolean transportSsl;

    public DbConnectionService(CloudBootstrapService cloudBootstrap) {
        this.cloudBootstrap = cloudBootstrap;
        // 全局静态设置（影响 JVM 内全部 DriverManager 调用）：进程内只有本服务直连
        // 外部数据库，构造期设置一次即可，不在每次建连时重复写全局状态
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
    }

    // ==================== 会话绑定（AI 按会话定向到库） ====================

    /**
     * 绑定「会话 → 连接」：该会话里智能体的 db_query 固定作用于这条连接（库）。
     * DB 工作区每个 (连接, 库) 一个智能体会话（前端 chat 消息携带 connKey 时调用）。
     */
    public void bindSession(String sessionId, String workspaceId, String connKey) {
        if (sessionId == null || sessionId.isBlank() || workspaceId == null
                || workspaceId.isBlank() || connKey == null || connKey.isBlank()) {
            return;
        }
        sessionConn.put(sessionId, workspaceId + "|" + connKey);
    }

    /** 解除会话绑定（WS 断连清理 orphaned 会话时调用，防 map 无界增长） */
    public void unbindSession(String sessionId) {
        if (sessionId != null) {
            sessionConn.remove(sessionId);
        }
    }

    /**
     * 会话绑定的连接键；未绑定或绑定不属于该工作区时返回 null（调用方回退最近连接）。
     * 校验 workspace 前缀，防止拿 A 工作区会话的绑定去操作 B 工作区的连接。
     */
    public String connKeyForSession(String sessionId, String workspaceId) {
        String key = sessionId == null ? null : sessionConn.get(sessionId);
        if (key == null || workspaceId == null || workspaceId.isBlank()
                || !key.startsWith(workspaceId + "|")) {
            return null;
        }
        return key.substring(workspaceId.length() + 1);
    }

    /** 工作区最近建立的活跃连接（未绑定会话时的回退目标）；无活跃连接返回 null。 */
    public String primaryConnKey(String workspaceId) {
        Map<String, DbSession> ws = sessions.get(workspaceId);
        if (ws == null || ws.isEmpty()) {
            return null;
        }
        DbSession best = null;
        for (DbSession s : ws.values()) {
            if (s.alive() && (best == null || s.connectedAt().isAfter(best.connectedAt()))) {
                best = s;
            }
        }
        return best == null ? null : best.connKey();
    }

    /**
     * 建立或复用 (serverKey, database) 物理连接。
     * 连接参数一律按 serverKey 从 hub 快照解析（前端传参不采信）；密码优先级同 ops：
     * 前端加密密码 > hub 下发密码。
     * <p>
     * 并发口径：同一 connKey 的并发 connect 以 {@code putIfAbsent} 定胜负——输家立即
     * 关闭自己刚建的物理连接，杜绝 check-then-act 覆盖导致的连接句柄泄漏。
     *
     * @return 连接键（serverKey + "/" + database），前端与工具层凭它引用会话
     */
    public String connect(String workspaceId, String serverKey, String database, String password) {
        SpokeDbConnectionView cfg = requireConfig(serverKey);
        String db = database == null || database.isBlank() ? cfg.databaseName() : database.trim();
        String connKey = connKey(serverKey, db);
        Map<String, DbSession> ws = sessions.computeIfAbsent(workspaceId, k -> new ConcurrentHashMap<>());
        DbSession existing = ws.get(connKey);
        if (existing != null && existing.alive()) {
            return connKey;
        }
        if (existing != null) {
            existing.closeQuietly(); // 死连接先清掉再重建
            ws.remove(connKey, existing); // 两参 remove：仅当仍映射到该死会话时移除（并发安全）
        }
        if (ws.size() >= MAX_CONNECTIONS_PER_WORKSPACE) {
            throw new ConnectionLimitException("活跃数据库连接数已达上限 " + MAX_CONNECTIONS_PER_WORKSPACE);
        }
        try {
            Connection conn = openConnection(cfg, db, password);
            DbSession session = new DbSession(conn, cfg, db, connKey);
            DbSession prev = ws.putIfAbsent(connKey, session);
            if (prev != null) {
                // 并发 connect 同一 connKey：对方已抢先注册，立即关闭本线程刚建的连接
                session.closeQuietly();
                return prev.connKey();
            }
            log.info("数据库连接建立: workspace={}, serverKey={}, db={}, product={}",
                    workspaceId, serverKey, db, session.product());
            return connKey;
        } catch (SQLException e) {
            log.warn("数据库连接失败: workspace={}, serverKey={}, db={}", workspaceId, serverKey, db, e);
            throw new IllegalStateException("数据库连接失败: " + describe(e), e);
        }
    }

    /** 活跃会话（工具层执行 SQL 用）；不存在或已死返回 null。 */
    public DbSession session(String workspaceId, String connKey) {
        Map<String, DbSession> ws = sessions.get(workspaceId);
        if (ws == null || connKey == null) {
            return null;
        }
        DbSession s = ws.get(connKey);
        return s != null && s.alive() ? s : null;
    }

    /**
     * 会话级 DB 上下文（V31）：随 chat 消息注入 LLM，让模型免探测即知连接基本信息
     * （类型/版本/主机/库/schema 清单/只读）。会话不存在或已断开返回 null（不注入）。
     */
    public String contextFor(String workspaceId, String connKey) {
        DbSession s = session(workspaceId, connKey);
        if (s == null) {
            return null;
        }
        String version = s.version() == null ? "" : s.version().split("\n")[0].trim();
        return "[DB 连接上下文] 数据库类型=" + s.dbType()
                + " 版本=" + version
                + " 主机=" + s.host() + ":" + s.port()
                + " 库=" + s.database()
                + (s.schemaHint() == null ? "" : " 有表的schema=" + s.schemaHint())
                + (s.readonlyHint() ? " 只读模式（仅允许 SELECT/WITH 查询）" : "")
                + "。表名可用 db_schema 工具查询，查询时使用 schema.table 限定名。";
    }

    /** 活跃连接快照（status 端点 / 前端渲染 / MCP db_status 的类型化契约）。 */
    public List<DbConnectionStatus> status(String workspaceId) {
        Map<String, DbSession> ws = sessions.get(workspaceId);
        List<DbConnectionStatus> out = new ArrayList<>();
        if (ws == null) {
            return out;
        }
        for (DbSession s : ws.values()) {
            out.add(DbConnectionStatus.from(s));
        }
        return out;
    }

    /** 断开指定连接；返回剩余活跃连接列表。 */
    public List<DbConnectionStatus> disconnect(String workspaceId, String connKey) {
        Map<String, DbSession> ws = sessions.get(workspaceId);
        if (ws != null) {
            DbSession s = ws.remove(connKey);
            if (s != null) {
                s.closeQuietly();
                log.info("数据库连接断开: workspace={}, connKey={}", workspaceId, connKey);
            }
        }
        return status(workspaceId);
    }

    /** 全部工作区的活跃连接快照（授权守卫用：批量取 serverKey 做 authorize-check）。 */
    public List<ActiveDbConn> activeDbConns() {
        List<ActiveDbConn> out = new ArrayList<>();
        for (var wsEntry : sessions.entrySet()) {
            for (DbSession s : wsEntry.getValue().values()) {
                if (s.alive()) {
                    out.add(new ActiveDbConn(wsEntry.getKey(), s.connKey(), s.serverKey()));
                }
            }
        }
        return out;
    }

    /** 活跃连接三元组（授权守卫消费）：workspaceId + connKey + serverKey。 */
    public record ActiveDbConn(String workspaceId, String connKey, String serverKey) {
    }

    /**
     * 列出该连接实例上可用的库/schema 清单（前端「库」二级选择器数据源）。
     * 用目录库（hub 下发的默认 databaseName）建<b>临时</b>连接查询，查完即关——
     * 不占用活跃连接槽位。四库查询（设计定稿）：
     * MySQL=information_schema.schemata；PostgreSQL=pg_namespace；
     * SQLServer=sys.databases；Oracle=all_users（schema 语义）。
     */
    public List<String> databases(String serverKey, String password) {
        SpokeDbConnectionView cfg = requireConfig(serverKey);
        String sql = switch (cfg.dbType()) {
            case "mysql" -> "SELECT schema_name FROM information_schema.schemata "
                    + "WHERE schema_name NOT IN ('information_schema','mysql','performance_schema','sys') ORDER BY 1";
            case "postgresql" -> "SELECT nspname FROM pg_namespace "
                    + "WHERE nspname NOT IN ('pg_catalog','information_schema','pg_toast') AND nspname NOT LIKE 'pg_temp%' ORDER BY 1";
            case "sqlserver" -> "SELECT name FROM sys.databases WHERE state = 0 "
                    + "AND name NOT IN ('master','tempdb','model','msdb') ORDER BY 1";
            case "oracle" -> "SELECT username FROM all_users ORDER BY 1";
            default -> throw new IllegalStateException("不支持的数据库类型: " + cfg.dbType());
        };
        try (Connection conn = openConnection(cfg, cfg.databaseName(), password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            log.warn("查询库清单失败: serverKey={}", serverKey, e);
            throw new IllegalStateException("查询库清单失败: " + describe(e), e);
        }
    }

    /**
     * 压缩 SQLException 因果链为一句可诊断描述：顶层消息 + 最深层根因（类名: 消息）。
     * JDBC 驱动常把真实原因（连接被拒/超时/DNS 解析失败/SSL 握手）挂在 cause 链上，
     * 只取 {@code getMessage()} 会得到「尝试连线已失败」这类无信息量的本地化文案。
     * 驱动异常消息只含 host/port 等连接目标信息、不含凭据，可安全透出给前端。
     */
    private static String describe(SQLException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == e || root.getMessage() == null || root.getMessage().isBlank()) {
            return e.getMessage();
        }
        return e.getMessage() + "（根因: " + root.getClass().getSimpleName() + ": " + root.getMessage() + "）";
    }

    // ==================== 连接构造 ====================

    /** hub 快照必须有该 serverKey 的启用配置，否则拒绝（同 ops：前端传参不采信）。 */
    private SpokeDbConnectionView requireConfig(String serverKey) {
        return cloudBootstrap.findDbConnection(serverKey)
                .orElseThrow(() -> new IllegalStateException(
                        "serverKey 不存在或平台配置未就绪: " + serverKey));
    }

    /** 按库类型构造 JDBC URL 并建连；Oracle/PG 建连后执行 schema 初始化。 */
    private Connection openConnection(SpokeDbConnectionView cfg, String database, String password)
            throws SQLException {
        String url = switch (cfg.dbType()) {
            // useSSL/allowPublicKeyRetrieval 联动：明文模式（内网现状）必须允许公钥检索
            // 才能完成 caching_sha2_password 认证；加密模式下关闭检索、凭证书交换
            case "mysql" -> "jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + database
                    + "?useSSL=" + transportSsl
                    + "&allowPublicKeyRetrieval=" + !transportSsl
                    + "&useUnicode=true&characterEncoding=utf8"
                    + "&connectTimeout=" + (CONNECT_TIMEOUT_SECONDS * 1000)
                    + "&socketTimeout=" + (SOCKET_TIMEOUT_SECONDS * 1000);
            case "postgresql" -> "jdbc:postgresql://" + cfg.host() + ":" + cfg.port() + "/" + database
                    + "?connectTimeout=" + CONNECT_TIMEOUT_SECONDS
                    + "&socketTimeout=" + SOCKET_TIMEOUT_SECONDS;
            case "sqlserver" -> "jdbc:sqlserver://" + cfg.host() + ":" + cfg.port()
                    + ";databaseName=" + database + ";encrypt=" + transportSsl
                    + ";loginTimeout=" + LOGIN_TIMEOUT_SECONDS;
            case "oracle" -> "jdbc:oracle:thin:@//" + cfg.host() + ":" + cfg.port() + "/" + database;
            default -> throw new IllegalStateException("不支持的数据库类型: " + cfg.dbType());
        };
        Connection conn = DriverManager.getConnection(url, cfg.username(), password);
        initSchema(conn, cfg.dbType(), database);
        return conn;
    }

    /**
     * schema 初始化（设计定稿）：Oracle = ALTER SESSION SET CURRENT_SCHEMA、
     * PostgreSQL = SET search_path。库名来自 hub 目录（非用户自由输入），仍用引号包裹防注入。
     * MySQL/SQLServer 库名已在 URL 里，无需初始化。
     */
    private void initSchema(Connection conn, String dbType, String database) throws SQLException {
        if ("oracle".equals(dbType)) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(database));
            }
        } else if ("postgresql".equals(dbType)) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO " + quoteIdentifier(database));
            }
        }
    }

    /** 标识符引号包裹：双引号内翻倍转义（Oracle/PG/SQLServer 通用）。 */
    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String connKey(String serverKey, String database) {
        return serverKey + "/" + database;
    }

    /**
     * 活跃数据库会话：持有物理连接 + 建连时的配置/版本快照（审计上报用）。
     * 不可变快照字段（serverName/host/...）取建连时刻值，连接断开后快照仍可读。
     * <p>
     * 线程口径：单条 JDBC Connection 非线程安全，而 agent 查询、授权守卫 isValid 探测、
     * MCP 通道可能并发触达同一会话——所有连接访问必须经 {@link #withConnection} 串行化
     * （synchronized 可重入，渲染器内部多条语句在一次持锁内完成）。
     */
    public static final class DbSession {

        /** 连接互斥访问的函数形态：允许实现抛 SQLException，调用方免包装。 */
        @FunctionalInterface
        public interface SqlAction<R> {
            R apply(Connection connection) throws SQLException;
        }

        private final Connection connection;
        private final String serverKey;
        private final String serverName;
        private final String dbType;
        private final String host;
        private final int port;
        private final String database;
        private final String username;
        private final boolean readonlyHint;
        private final String product;
        private final String version;
        private final Instant connectedAt;
        private final String connKey;
        /** 建连时探测的「有用户表的 schema」清单（逗号拼接，仅 PG；探测失败为 null）。
         *  PG 的 schema ≠ 库，表常落在非默认 schema（如 hub）——注入 LLM 上下文用，
         *  免去模型盲目探测。MySQL 的 schema=库（无需单独提示），其他库型暂不探测。 */
        private final String schemaHint;

        DbSession(Connection connection, SpokeDbConnectionView cfg, String database, String connKey)
                throws SQLException {
            this.connection = connection;
            this.serverKey = cfg.serverKey();
            this.serverName = cfg.name();
            this.dbType = cfg.dbType();
            this.host = cfg.host();
            this.port = cfg.port();
            this.database = database;
            this.username = cfg.username();
            this.readonlyHint = cfg.readonlyHint();
            // 版本探测（设计定稿）：DatabaseMetaData 统一取，不写方言 SQL
            this.product = connection.getMetaData().getDatabaseProductName();
            this.version = connection.getMetaData().getDatabaseProductVersion();
            this.connectedAt = Instant.now();
            this.connKey = connKey;
            this.schemaHint = probeSchemaHint(connection, cfg.dbType());
        }

        /**
         * 连接互斥访问入口：持会话锁执行动作。JDBC Connection 非线程安全，
         * 任何 SQL 执行/元数据读取都必须走本方法，禁止直接暴露 Connection。
         */
        public synchronized <R> R withConnection(SqlAction<R> action) throws SQLException {
            return action.apply(connection);
        }

        public String serverKey() {
            return serverKey;
        }

        public String serverName() {
            return serverName;
        }

        public String dbType() {
            return dbType;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String database() {
            return database;
        }

        public String username() {
            return username;
        }

        public boolean readonlyHint() {
            return readonlyHint;
        }

        public String product() {
            return product;
        }

        public String version() {
            return version;
        }

        public Instant connectedAt() {
            return connectedAt;
        }

        public String schemaHint() {
            return schemaHint;
        }

        public String connKey() {
            return connKey;
        }

        /** 连接是否仍可用（轻量 isValid 探测，1 秒超时）；与查询互斥，最坏让查询等 1s。 */
        synchronized boolean alive() {
            try {
                return connection != null && !connection.isClosed() && connection.isValid(1);
            } catch (SQLException e) {
                return false;
            }
        }

        /** 关闭连接（幂等）；与查询互斥——进行中的查询结束后才真正 close。 */
        synchronized void closeQuietly() {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                log.debug("关闭数据库连接失败（忽略）: {}", e.getMessage());
            }
        }

        /**
         * 建连时探测「有用户表的 schema」清单（仅 PG）：PG 的 schema ≠ 库，表常落在
         * 非默认 schema（如 hub），不提示的话模型要盲查 information_schema 才能发现。
         * 探测失败返回 null——建连不因元数据探测失败而阻断。
         */
        private static String probeSchemaHint(Connection connection, String dbType) {
            if (!"postgresql".equalsIgnoreCase(dbType)) {
                return null;
            }
            try (java.sql.Statement st = connection.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(
                         "SELECT string_agg(DISTINCT table_schema, ', ' ORDER BY table_schema) "
                                 + "FROM information_schema.tables "
                                 + "WHERE table_schema <> 'information_schema' AND table_schema NOT LIKE 'pg_%'")) {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                log.debug("schema 清单探测失败（忽略）: {}", e.getMessage());
                return null;
            }
        }
    }

    /** 工作区连接数超上限（控制器映射 429，区别于建连失败的 502）。 */
    public static final class ConnectionLimitException extends IllegalStateException {
        public ConnectionLimitException(String message) {
            super(message);
        }
    }

    /**
     * 活跃连接状态快照（status 端点 / 前端渲染 / MCP db_status 的类型化契约）：
     * 字段名与前端 {@code ActiveDbConn} interface 一一对应，替代此前的
     * {@code Map<String,Object>} 弱类型输出。connectedAt 保持字符串（ISO-8601），
     * 与既有 JSON 形状完全兼容。
     */
    public record DbConnectionStatus(String connKey, String serverKey, String serverName,
                                     String dbType, String host, int port, String database,
                                     String username, boolean readonlyHint, String product,
                                     String version, String connectedAt, boolean alive) {

        static DbConnectionStatus from(DbSession s) {
            return new DbConnectionStatus(s.connKey(), s.serverKey(), s.serverName(), s.dbType(),
                    s.host(), s.port(), s.database(), s.username(), s.readonlyHint(),
                    s.product(), s.version(), s.connectedAt().toString(), s.alive());
        }
    }
}
