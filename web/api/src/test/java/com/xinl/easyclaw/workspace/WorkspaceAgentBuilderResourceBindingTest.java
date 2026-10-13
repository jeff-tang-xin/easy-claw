package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.type.BuiltinMcpRegistry;
import com.xinl.easyclaw.ops.api.OpsMcpTools;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.tools.DbTools;
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
 * 工作区资源绑定 → db 直注册 / ops 派生工具的装配规则。
 * <p>
 * 「绑定了就该有工具、没绑定就不该有、与场景实例同名要让位」——这三条决定这个 AI
 * 到底能碰哪台机器哪个库，只能在这里钉住：端到端联调覆盖不了所有组合，
 * 而注册顺序覆盖工具这种事故在日志里几乎看不出来。
 * <p>
 * V32 定稿：db 不走内置 MCP 类型（那条路对 db 是半成品），绑定后<b>直注册</b> DbTools
 * 并裁剪到 SOLO 工具面（db_query/db_dml；db_ddl 只属于 DB 工作台）；ops 仍走派生。
 */
@DisplayName("工作区资源绑定 → db 直注册 / ops 派生")
class WorkspaceAgentBuilderResourceBindingTest {

    private static final String OPS_CONFIG = "{\"serverKey\":\"prod-web\"}";

    private OpsMcpTools opsType;
    private BuiltinMcpRegistry registry;
    private DbTools dbTools;

    @BeforeEach
    void setUp() {
        opsType = new OpsMcpTools(mock(SshConnectionService.class),
                mock(CloudBootstrapService.class), mock(OpsTools.class));
        registry = new BuiltinMcpRegistry(
                List.<com.xinl.easyclaw.mcp.type.BuiltinMcpType>of(opsType));
        // 注册只反射 @Tool 方法签名，服务依赖不参与调用——null 即可
        dbTools = new DbTools(null, null, null);
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
    @DisplayName("绑定了 db 与 ops：SOLO 拿到 db_query/db_dml/db_schema（无 ddl 与报表辅助）+ remote_shell")
    void boundResourcesProduceTools() {
        Toolkit toolkit = new Toolkit();

        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-1", "prod-mysql", "prod-web", dbTools);

        assertThat(names(toolkit)).contains("db_query", "db_dml", "db_schema", "remote_shell")
                .doesNotContain("db_ddl", "db_status", "db_report", "db_dashboard");
        assertThat(registered).containsExactlyInAnyOrder("db_query", "db_dml", "db_schema", "remote_shell");
    }

    @Test
    @DisplayName("什么都不绑：工具面干干净净，没有 db_* 也没有 remote_shell")
    void noBindingMeansNoCapability() {
        Toolkit toolkit = new Toolkit();

        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-2", null, "", dbTools);

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
                toolkit, registry, "ws-3", "prod-mysql", "other-box", dbTools);

        // db 侧照常直注册；ops 侧让位——返回值不含 remote_shell，工具面也没有多出第二个
        assertThat(registered).containsExactlyInAnyOrder("db_query", "db_dml", "db_schema");
        assertThat(names(toolkit)).containsExactlyInAnyOrder(
                "db_query", "db_dml", "db_schema", "remote_shell");
    }

    @Test
    @DisplayName("注册面不校验资源是否仍存在：撤销的资源也照常注册工具，拒绝发生在执行时")
    void registrationDoesNotValidateResourceExistence() {
        Toolkit toolkit = new Toolkit();

        // serverKey 随便填：注册只负责「能力在不在」，「目标还归不归这个项目」由执行路径
        // fail-closed 校验。若在这里就校验，撤销资源会让已绑定工作区的工具面悄悄消失，
        // 用户只会看到「AI 突然不会查库了」，比执行时报错难排查得多。
        List<String> registered = WorkspaceAgentBuilder.registerBoundResources(
                toolkit, registry, "ws-4", "revoked-db", "revoked-box", dbTools);

        assertThat(registered).contains("db_query", "remote_shell");
    }
}
