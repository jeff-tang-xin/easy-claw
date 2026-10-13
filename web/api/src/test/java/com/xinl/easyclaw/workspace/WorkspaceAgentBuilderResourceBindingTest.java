package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.db.api.DbMcpTools;
import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.db.service.DbQueryExecutor;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.type.BuiltinMcpRegistry;
import com.xinl.easyclaw.ops.api.OpsMcpTools;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.tools.OpsTools;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 工作区资源绑定 → 内置能力工具的派生规则。
 * <p>
 * 「绑定了就该有工具、没绑定就不该有、与场景实例同名要让位」——这三条决定这个 AI
 * 到底能碰哪台机器哪个库，只能在这里钉住：端到端联调覆盖不了所有组合，
 * 而注册顺序覆盖工具这种事故在日志里几乎看不出来。
 */
@DisplayName("工作区资源绑定 → 内置能力工具派生")
class WorkspaceAgentBuilderResourceBindingTest {

    private static final String DB_CONFIG = "{\"serverKey\":\"prod-mysql\",\"database\":\"orders_db\"}";
    private static final String OPS_CONFIG = "{\"serverKey\":\"prod-web\"}";

    private OpsMcpTools opsType;
    private BuiltinMcpRegistry registry;

    @BeforeEach
    void setUp() {
        DbMcpTools dbType = new DbMcpTools(mock(DbConnectionService.class),
                mock(DbQueryExecutor.class), mock(CloudBootstrapService.class));
        opsType = new OpsMcpTools(mock(SshConnectionService.class),
                mock(CloudBootstrapService.class), mock(OpsTools.class));
        registry = new BuiltinMcpRegistry(
                List.<com.xinl.easyclaw.mcp.type.BuiltinMcpType>of(dbType, opsType));
    }

    private static List<String> names(Toolkit toolkit) {
        return toolkit.getToolSchemas().stream()
                .map(io.agentscope.core.model.ToolSchema::getName)
                .toList();
    }

    private static McpServiceEntity instance(String mcpType, String config) {
        return McpServiceEntity.builder()
                .id(1L).name("manual-" + mcpType)
                .transport("BUILTIN").mcpType(mcpType)
                .scope("GLOBAL").implementationConfig(config)
                .build();
    }

    @Test
    @DisplayName("绑定了 db 与 ops：工具面出现对应能力，返回值如实记录")
    void boundResourcesProduceTools() {
        Toolkit toolkit = new Toolkit();

        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-1", "prod-mysql", "prod-web");

        assertThat(names(toolkit)).contains("db_query", "db_schema", "db_status", "remote_shell");
        assertThat(registered).containsExactlyInAnyOrder("db_query", "db_schema", "db_status", "remote_shell");
    }

    @Test
    @DisplayName("什么都不绑：工具面干干净净，没有 db_* 也没有 remote_shell")
    void noBindingMeansNoCapability() {
        Toolkit toolkit = new Toolkit();

        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-2", null, "");

        assertThat(registered).isEmpty();
        assertThat(names(toolkit)).noneMatch(n -> n.startsWith("db_"));
        assertThat(names(toolkit)).doesNotContain("remote_shell");
    }

    @Test
    @DisplayName("场景实例已提供同名工具时让位：不覆盖、不重复注册")
    void yieldsToExistingSameNameTool() {
        Toolkit toolkit = new Toolkit();
        // 模拟场景绑定先注册的 ops 实例（它锁的是另一台机器）
        for (AgentTool tool : opsType.toolsFor(instance("ops", OPS_CONFIG))) {
            toolkit.registerAgentTool(tool);
        }

        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-3", "prod-mysql", "other-box");

        // db 侧照常派生；ops 侧让位——返回值不含 remote_shell，工具面也没有多出第二个
        // （顺序不承诺：toolsFor 的装配顺序是实现细节，断言只看集合；anyOrder 同时隐含「无重复」）
        assertThat(registered).containsExactlyInAnyOrder("db_query", "db_schema", "db_status");
        assertThat(names(toolkit)).containsExactlyInAnyOrder(
                "remote_shell", "db_query", "db_schema", "db_status");
    }

    @Test
    @DisplayName("注册面不校验资源是否仍存在：撤销的资源也照常派生工具，拒绝发生在执行时")
    void registrationDoesNotValidateResourceExistence() {
        Toolkit toolkit = new Toolkit();

        // serverKey 随便填：注册只负责「能力在不在」，「目标还归不归这个项目」由执行路径
        // fail-closed 校验。若在这里就校验，撤销资源会让已绑定工作区的工具面悄悄消失，
        // 用户只会看到「AI 突然不会查库了」，比执行时报错难排查得多。
        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-4", "revoked-db", "revoked-box");

        assertThat(registered).contains("db_query", "remote_shell");
    }
}
