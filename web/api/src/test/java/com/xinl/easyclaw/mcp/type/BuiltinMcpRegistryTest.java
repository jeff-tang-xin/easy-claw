package com.xinl.easyclaw.mcp.type;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置 MCP 类型注册表行为锁。
 * <p>
 * 重点锁住「按类型分派」这件事：历史实现按服务名（{@code easyclaw-db}）识别内置服务，
 * 导致改名即静默失效、且永远只能有一个实例。注册表是这套新口径的唯一入口。
 */
@DisplayName("BuiltinMcpRegistry 内置类型注册表")
class BuiltinMcpRegistryTest {

    /** 最小假类型：只关心 typeId 与工具面数量 */
    private record FakeType(String typeId, int toolCount) implements BuiltinMcpType {
        @Override
        public String typeId() {
            return typeId;
        }

        @Override
        public String displayName() {
            return "假类型 " + typeId;
        }

        @Override
        public List<Map<String, Object>> configSchema() {
            return List.of(Map.of("key", "serverKey", "required", true));
        }

        @Override
        public List<AgentTool> toolsFor(McpServiceEntity instance) {
            return java.util.stream.IntStream.rangeClosed(1, toolCount)
                    .mapToObj(i -> (AgentTool) new NoopTool(typeId + "_" + i))
                    .toList();
        }
    }

    private record NoopTool(String name) implements AgentTool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("noop"));
        }
    }

    @Test
    @DisplayName("按 typeId 查找：大小写与空白不影响命中")
    void findIsCaseAndSpaceInsensitive() {
        BuiltinMcpRegistry registry = new BuiltinMcpRegistry(List.of(new FakeType("db", 3)));

        assertThat(registry.find("db")).isPresent();
        assertThat(registry.find("DB")).isPresent();
        assertThat(registry.find("  db  ")).isPresent();
        assertThat(registry.supports("Db")).isTrue();
        assertThat(registry.supports("ops-server")).isFalse();
    }

    @Test
    @DisplayName("同 typeId 重复注册：保留先注册的，不静默覆盖")
    void duplicateTypeIdKeepsFirst() {
        FakeType first = new FakeType("db", 3);
        FakeType second = new FakeType("db", 1);

        BuiltinMcpRegistry registry = new BuiltinMcpRegistry(List.of(first, second));

        assertThat(registry.find("db")).contains(first);
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    @DisplayName("typeId 为空的实现被跳过，不污染注册表")
    void blankTypeIdIsSkipped() {
        BuiltinMcpRegistry registry = new BuiltinMcpRegistry(
                List.of(new FakeType("  ", 1), new FakeType("db", 3)));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0).typeId()).isEqualTo("db");
    }

    @Test
    @DisplayName("all() 按 typeId 字典序，前端下拉顺序稳定")
    void allIsSortedByTypeId() {
        BuiltinMcpRegistry registry = new BuiltinMcpRegistry(
                List.of(new FakeType("ops-server", 1), new FakeType("db", 3)));

        assertThat(registry.all()).extracting(BuiltinMcpType::typeId)
                .containsExactly("db", "ops-server");
    }

    @Test
    @DisplayName("toolsFor 可从实例配置静态推导：未连接也能拿到工具清单")
    void toolsForWorksWithoutConnection() {
        BuiltinMcpType type = new FakeType("db", 3);
        McpServiceEntity instance = McpServiceEntity.builder()
                .name("prod-orders")
                .transport("BUILTIN")
                .mcpType("db")
                .build();

        // 白名单展开必须在实例「激活之前」就能拿到清单，否则子智能体会静默失能
        assertThat(type.toolsFor(instance)).hasSize(3);
        assertThat(type.toolsFor(instance)).extracting(AgentTool::getName)
                .containsExactly("db_1", "db_2", "db_3");
    }
}
