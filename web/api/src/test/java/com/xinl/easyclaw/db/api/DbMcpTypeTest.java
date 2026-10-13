package com.xinl.easyclaw.db.api;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeDbConnectionView;
import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryExecutor;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import io.agentscope.core.tool.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * db 内置类型的工具面装配规则 —— 锁住<b>「只暴露能力，不暴露地址」</b>。
 * <p>
 * 历史上 db 工具带 {@code connKey} 参数（一个服务要服务多个库，只能让 LLM 每次复述连接键）。
 * 类型化后实例锁定自己的目标，跨库的正确表达是「再建一个实例」，于是参数面与工具描述里
 * 都不该再出现连接标识：一旦出现，等于把基础设施拓扑交给模型上下文，并顺手开出
 * 「诱导它去操作授权外目标」的越权面。
 * <p>
 * 全部用<b>静态断言</b>（schema + 描述文本）来锁：不依赖真实连接，也不依赖调用时序，
 * 任何人日后往参数面加回地址类字段都会在这里当场失败。
 */
@DisplayName("db 内置类型：能力面不暴露连接标识")
class DbMcpTypeTest {

    private static final String LOCKED = "{\"serverKey\":\"prod-mysql\",\"database\":\"orders_db\"}";

    private DbConnectionService db;
    private CloudBootstrapService cloudBootstrap;
    private DbMcpTools type;

    @BeforeEach
    void setUp() {
        db = mock(DbConnectionService.class);
        cloudBootstrap = mock(CloudBootstrapService.class);
        type = new DbMcpTools(db, mock(DbQueryExecutor.class), cloudBootstrap);
    }

    private McpServiceEntity instance(String implementationConfig) {
        return McpServiceEntity.builder()
                .id(1L)
                .name("prod-orders")
                .transport("BUILTIN")
                .mcpType("db")
                .scope("GLOBAL")
                .implementationConfig(implementationConfig)
                .build();
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未装配工具: " + name));
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(List<AgentTool> tools, String name) {
        return (List<String>) tool(tools, name).getParameters().get("required");
    }

    @SuppressWarnings("unchecked")
    private static List<String> propsOf(List<AgentTool> tools, String name) {
        Map<String, Object> props = (Map<String, Object>) tool(tools, name).getParameters().get("properties");
        return List.copyOf(props.keySet());
    }

    /** 工具面全貌：名称 + 描述 + 参数名 + 参数描述，用于「不该出现某字样」的全局断言 */
    private static String surfaceOf(List<AgentTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (AgentTool t : tools) {
            sb.append(t.getName()).append('\n').append(t.getDescription()).append('\n')
                    .append(t.getParameters()).append('\n');
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    @DisplayName("类型契约：typeId=db，配置表单要求选 hub 下发的连接")
    void typeContract() {
        assertThat(type.typeId()).isEqualTo("db");
        assertThat(type.configSchema()).extracting(f -> f.get("key"))
                .containsExactly("serverKey", "database", "enableDdl");
        assertThat(type.configSchema().get(0).get("optionsSource"))
                .isEqualTo("hub-db-connections");
    }

    @Test
    @DisplayName("参数面只有业务参数：db_query/db_dml=sql、db_schema=table、db_status 无参；db_ddl 默认不装配")
    void parametersOnlyCarryBusinessArgs() {
        List<AgentTool> tools = type.toolsFor(instance(LOCKED));

        assertThat(tools).extracting(AgentTool::getName)
                .containsExactly("db_status", "db_schema", "db_query", "db_dml");
        assertThat(propsOf(tools, "db_query")).containsExactly("sql");
        assertThat(propsOf(tools, "db_schema")).containsExactly("table");
        assertThat(propsOf(tools, "db_status")).isEmpty();
        assertThat(propsOf(tools, "db_dml")).containsExactly("sql");
    }

    @Test
    @DisplayName("required 恒定不含连接标识：无论实例是否锁定目标")
    void requiredNeverCarriesConnKey() {
        List<String> configs = List.of(LOCKED, "{\"serverKey\":\"prod-mysql\"}",
                "{\"serverKey\":\"  \",\"database\":\"orders_db\"}", "{not-json");
        for (String config : configs) {
            List<AgentTool> tools = type.toolsFor(instance(config));

            assertThat(requiredOf(tools, "db_query"))
                    .as("config=%s", config)
                    .containsExactly("sql");
            assertThat(requiredOf(tools, "db_schema"))
                    .as("config=%s", config)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("工具面任何字样里都不出现 connKey / serverKey / host / port / password")
    void surfaceNeverLeaksLocators() {
        String surface = surfaceOf(type.toolsFor(instance(LOCKED)));

        assertThat(surface)
                .doesNotContain("connkey")
                .doesNotContain("serverkey")
                .doesNotContain("host")
                .doesNotContain("port")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("存量 SYSTEM 单例无实例配置 → 同样只暴露能力（目标靠会话绑定在服务端解析）")
    void legacySingletonWithoutConfigStillHidesLocators() {
        List<AgentTool> tools = type.toolsFor(instance(null));

        assertThat(tools).hasSize(4);
        assertThat(surfaceOf(tools)).doesNotContain("connkey").doesNotContain("serverkey");
        assertThat(requiredOf(tools, "db_query")).containsExactly("sql");
    }

    @Test
    @DisplayName("hub 快照取不到默认库 → 照常装配四工具，不因此冒出连接参数")
    void missingDefaultDatabaseDoesNotReintroduceConnKey() {
        when(cloudBootstrap.findDbConnection(anyString())).thenReturn(Optional.empty());

        List<AgentTool> tools = type.toolsFor(instance("{\"serverKey\":\"prod-mysql\"}"));

        assertThat(tools).hasSize(4);
        assertThat(surfaceOf(tools)).doesNotContain("connkey");
    }

    @Test
    @DisplayName("只给 serverKey 时用 hub 下发的默认库补齐锁定")
    void serverKeyOnlyFallsBackToHubDefaultDatabase() {
        when(cloudBootstrap.findDbConnection("prod-mysql")).thenReturn(Optional.of(
                new SpokeDbConnectionView("prod-mysql", "生产库", "mysql",
                        "10.0.0.1", 3306, "orders_db", "reader", null, true, 1L, null, true)));

        List<AgentTool> tools = type.toolsFor(instance("{\"serverKey\":\"prod-mysql\"}"));

        assertThat(tools).hasSize(4);
        assertThat(requiredOf(tools, "db_query")).containsExactly("sql");
    }

    @Test
    @DisplayName("配置脏数据（非法 JSON）不抛异常，降级为未锁定")
    void malformedConfigDegradesWithoutThrowing() {
        assertThat(type.toolsFor(instance("{not-json"))).hasSize(4);
    }

    @Test
    @DisplayName("DDL 开关：默认不装配 db_ddl，实例配置显式开放后才出现（fail-closed）")
    void ddlToolOnlyAssembledWhenExplicitlyEnabled() {
        // 默认（无 enableDdl 字段）：不装配
        assertThat(toolNames(type.toolsFor(instance(LOCKED)))).doesNotContain("db_ddl");

        // 字符串 "true"（前端 checkbox 以字符串存取）：装配
        assertThat(toolNames(type.toolsFor(instance(
                "{\"serverKey\":\"prod-mysql\",\"database\":\"orders_db\",\"enableDdl\":\"true\"}"))))
                .contains("db_ddl");

        // JSON boolean true：装配
        assertThat(toolNames(type.toolsFor(instance(
                "{\"serverKey\":\"prod-mysql\",\"enableDdl\":true}")))).contains("db_ddl");

        // "false" / 无法识别的值 / 脏 JSON：一律不装配
        assertThat(toolNames(type.toolsFor(instance(
                "{\"serverKey\":\"prod-mysql\",\"enableDdl\":\"false\"}")))).doesNotContain("db_ddl");
        assertThat(toolNames(type.toolsFor(instance(
                "{\"serverKey\":\"prod-mysql\",\"enableDdl\":\"yes\"}")))).doesNotContain("db_ddl");
        assertThat(toolNames(type.toolsFor(instance("{not-json")))).doesNotContain("db_ddl");
    }

    @Test
    @DisplayName("开放 DDL 后工具面仍不泄漏连接标识")
    void ddlEnabledSurfaceStillHidesLocators() {
        String surface = surfaceOf(type.toolsFor(instance(
                "{\"serverKey\":\"prod-mysql\",\"database\":\"orders_db\",\"enableDdl\":\"true\"}")));

        assertThat(surface)
                .doesNotContain("connkey")
                .doesNotContain("serverkey")
                .doesNotContain("host")
                .doesNotContain("port")
                .doesNotContain("password");
    }

    private static List<String> toolNames(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::getName).toList();
    }
}
