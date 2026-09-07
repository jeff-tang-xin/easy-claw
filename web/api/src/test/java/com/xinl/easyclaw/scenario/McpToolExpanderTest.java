package com.xinl.easyclaw.scenario;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.service.McpConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpToolExpander} 服务名 → 工具名展开测试。
 * <p>
 * 重点覆盖「降级不阻断」：MCP 服务不存在、未连接过、JSON 损坏等异常配置
 * 都不得抛异常打断工作区加载，只能跳过并告警。场景配置错误让整个工作区起不来，
 * 是比少几个工具严重得多的故障。
 */
class McpToolExpanderTest {

    private McpConnectionService mcpService;
    private McpToolExpander expander;

    @BeforeEach
    void setUp() {
        mcpService = mock(McpConnectionService.class);
        expander = new McpToolExpander(mcpService);
    }

    private McpServiceEntity service(Long id, String name, String availableToolsJson) {
        McpServiceEntity e = new McpServiceEntity();
        e.setId(id);
        e.setName(name);
        e.setAvailableTools(availableToolsJson);
        return e;
    }

    @Test
    void 应把服务名展开为其全部工具名() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "filesystem",
                        "[{\"name\":\"fs_read\"},{\"name\":\"fs_write\"}]"),
                service(2L, "github", "[{\"name\":\"gh_search\"}]")));
        when(mcpService.getEnabledTools(anyLong())).thenReturn(List.of());

        Set<String> tools = expander.expand(List.of("filesystem"));

        assertThat(tools).containsExactly("fs_read", "fs_write");
        // 未绑定的服务工具不得泄漏进来（硬隔离的核心）
        assertThat(tools).doesNotContain("gh_search");
    }

    @Test
    void 多个服务应合并且去重() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "a", "[{\"name\":\"shared\"},{\"name\":\"only_a\"}]"),
                service(2L, "b", "[{\"name\":\"shared\"},{\"name\":\"only_b\"}]")));
        when(mcpService.getEnabledTools(anyLong())).thenReturn(List.of());

        Set<String> tools = expander.expand(List.of("a", "b"));

        assertThat(tools).containsExactlyInAnyOrder("shared", "only_a", "only_b");
    }

    @Test
    void 服务名匹配应大小写不敏感并忽略首尾空格() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "FileSystem", "[{\"name\":\"fs_read\"}]")));
        when(mcpService.getEnabledTools(anyLong())).thenReturn(List.of());

        assertThat(expander.expand(List.of("  filesystem  "))).containsExactly("fs_read");
    }

    @Test
    void 服务自身的enabledTools应与可用工具取交集() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "fs",
                        "[{\"name\":\"fs_read\"},{\"name\":\"fs_write\"},{\"name\":\"fs_rm\"}]")));
        // 服务级只放开 read/write，场景绑定不得绕过这层开关
        when(mcpService.getEnabledTools(1L)).thenReturn(List.of("fs_read", "fs_write"));

        Set<String> tools = expander.expand(List.of("fs"));

        assertThat(tools).containsExactly("fs_read", "fs_write");
        assertThat(tools).doesNotContain("fs_rm");
    }

    @Test
    void 空或null绑定应返回空集表示不限制() {
        assertThat(expander.expand(null)).isEmpty();
        assertThat(expander.expand(List.of())).isEmpty();
    }

    @Test
    void 服务不存在时应跳过而不抛异常() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "fs", "[{\"name\":\"fs_read\"}]")));
        when(mcpService.getEnabledTools(anyLong())).thenReturn(List.of());

        Set<String> tools = expander.expand(List.of("not_exist", "fs"));

        // 坏名字被跳过，好名字照常生效
        assertThat(tools).containsExactly("fs_read");
    }

    @Test
    void 服务从未连接过导致工具清单为空时应跳过() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "never_connected", null),
                service(2L, "blank", "  ")));

        assertThat(expander.expand(List.of("never_connected", "blank"))).isEmpty();
    }

    @Test
    void 工具清单JSON损坏时应降级为空而不抛异常() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "broken", "{not a json array")));

        assertThat(expander.expand(List.of("broken"))).isEmpty();
    }

    @Test
    void 查询MCP服务列表失败时应降级为空而不抛异常() {
        when(mcpService.findAll()).thenThrow(new RuntimeException("数据库不可用"));

        assertThat(expander.expand(List.of("fs"))).isEmpty();
    }

    @Test
    void 绑定项中的空白名字应被忽略() {
        when(mcpService.findAll()).thenReturn(List.of(
                service(1L, "fs", "[{\"name\":\"fs_read\"}]")));
        when(mcpService.getEnabledTools(anyLong())).thenReturn(List.of());

        // 前端表单常留下空字符串项
        java.util.List<String> raw = new java.util.ArrayList<>();
        raw.add("");
        raw.add("   ");
        raw.add(null);
        raw.add("fs");

        assertThat(expander.expand(raw)).containsExactly("fs_read");
    }
}
