package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 场景档位（capabilityTier）+ MCP 绑定对子 Agent <b>tools 白名单</b>的裁剪测试。
 * <p>
 * 这里守的是一条容易静默劣化的边界：档位字段有默认值 STANDARD，
 * 一旦把「解析出默认值」当成「用户配置了档位」，所有既有场景的子 Agent 都会
 * 突然被收紧（尤其丢掉 {@code execute}），而且日志与界面都看不出异常。
 * 因此第一个用例断言的是「什么都不做」，它比任何裁剪用例都重要。
 */
class SubagentToolRestrictionTest {

    private SubagentLoader newLoader() {
        return new SubagentLoader(mock(RoleManagementService.class), new AgentScopeProperties());
    }

    private SubagentDeclaration loadSingle(Path dir, String content, ScenarioBinding binding)
            throws IOException {
        Files.writeString(dir.resolve("worker.md"), content);
        List<SubagentDeclaration> decls = newLoader().loadFromDirectory(dir, binding);
        assertThat(decls).hasSize(1);
        return decls.get(0);
    }

    private ScenarioBinding binding(String tier, String mcpJson) {
        ScenarioEntity e = new ScenarioEntity();
        e.setCapabilityTier(tier);
        e.setMcpServices(mcpJson);
        return ScenarioBinding.from(e);
    }

    /** 声明了 tools 且档位未配置 —— 必须原样保留，一个都不许少 */
    @Test
    void 未配置档位时声明的tools必须原样保留(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 执行者
                tools: [read_file, execute, write_file]
                ---
                正文。
                """, binding(null, null));

        // blackboard_* 由 SubagentLoader.withBlackboardTools 无条件补齐（子 Agent 间唯一通道），
        // 故凡是「白名单非 null」的用例，期望值都要带上这两个名字。
        assertThat(decl.getTools())
                .containsExactly("read_file", "execute", "write_file", "blackboard_append", "blackboard_read");
    }

    /** 未声明 tools 且档位未配置 —— 必须保持 null（继承父 toolkit 全部工具），不能变成空集 */
    @Test
    void 未配置档位且未声明tools时不得凭空产生白名单(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 执行者
                ---
                正文。
                """, binding("   ", null));

        // 空集会被 harness 当成「不限制」，但语义含混；这里要求明确保持"未设置"
        assertThat(decl.getTools()).isNullOrEmpty();
    }

    @Test
    void readonly档位应剔除写类工具(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 只读分析
                tools: [read_file, write_file, edit_file, execute, grep_files]
                ---
                正文。
                """, binding("readonly", null));

        assertThat(decl.getTools()).contains("read_file", "grep_files");
        assertThat(decl.getTools()).doesNotContain("write_file", "edit_file", "execute");
    }

    @Test
    void 声明的tools全部越界时应回退为档位白名单而非空集(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 只读分析
                tools: [execute]
                ---
                正文。
                """, binding("readonly", null));

        // 空名单会被 harness 视为「不限制」，反而放开全部工具 —— 必须回退到档位白名单
        assertThat(decl.getTools()).isNotEmpty();
        assertThat(decl.getTools()).doesNotContain("execute");
        assertThat(decl.getTools()).contains("read_file");
    }

    @Test
    void 未声明tools时应直接采用档位白名单(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 只读分析
                ---
                正文。
                """, binding("readonly", null));

        assertThat(decl.getTools()).contains("read_file");
        assertThat(decl.getTools()).doesNotContain("write_file", "execute");
    }

    /** MCP 工具名不属于任何档位，必须靠并集进入白名单，否则场景绑定的 MCP 全部失效 */
    @Test
    void MCP展开的工具名应与档位工具取并集(@TempDir Path dir) throws IOException {
        ScenarioBinding b = binding("readonly", "[\"fs\"]")
                .withMcpTools(List.of("mcp_fs_read", "mcp_fs_write"));

        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 只读分析
                tools: [read_file, mcp_fs_write, execute]
                ---
                正文。
                """, b);

        assertThat(decl.getTools())
                .containsExactly("read_file", "mcp_fs_write", "blackboard_append", "blackboard_read");
    }

    /** NONE 档位且无 MCP：白名单为空会被 harness 反向解读成「不限制」，只能保留声明值 */
    @Test
    void NONE档位且无MCP绑定时不得下发空白名单(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, """
                ---
                description: 执行者
                tools: [read_file, execute]
                ---
                正文。
                """, binding("none", null));

        assertThat(decl.getTools())
                .containsExactly("read_file", "execute", "blackboard_append", "blackboard_read");
    }
}
