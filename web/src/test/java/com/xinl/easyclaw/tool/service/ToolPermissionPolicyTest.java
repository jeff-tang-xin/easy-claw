package com.xinl.easyclaw.tool.service;

import com.xinl.easyclaw.workspace.WorkspaceAgentBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具确认策略回归测试。
 * <p>
 * 保护三件事：
 * <ol>
 *   <li>{@code requiresConfirm} 与「静默放行清单」严格互补 —— 前端授权页面靠它过滤，
 *       判反了会导致「该授权的工具没有入口」或「点了开关没反应」</li>
 *   <li>写/执行类工具永远落在需确认侧（防止有人手滑把 execute 加进只读清单）</li>
 *   <li>{@code WorkspaceAgentBuilder} 构建权限上下文用的就是这份清单 ——
 *       用反射交叉校验，而不是在测试里手抄一份名单（手抄的名单会随代码腐烂而静默失效）</li>
 * </ol>
 */
class ToolPermissionPolicyTest {

    @Test
    @DisplayName("静默放行清单内的工具不需要确认")
    void silentlyAllowedToolsDoNotRequireConfirm() {
        for (String tool : ToolPermissionPolicy.silentlyAllowed()) {
            assertThat(ToolPermissionPolicy.requiresConfirm(tool))
                    .as("只读工具 %s 应静默放行", tool)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("写/执行类工具必须需要确认")
    void writeAndExecuteToolsRequireConfirm() {
        for (String tool : ToolPermissionPolicy.explicitAsk()) {
            assertThat(ToolPermissionPolicy.requiresConfirm(tool))
                    .as("写/执行工具 %s 必须征求确认", tool)
                    .isTrue();
        }
        // 显式点名最危险的几个：它们绝不能因为清单被误改而变成静默执行
        assertThat(ToolPermissionPolicy.requiresConfirm("execute")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm("write_file")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm("edit_file")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm("memory_save")).isTrue();
    }

    @Test
    @DisplayName("两个清单不相交：同一工具不能既静默放行又需确认")
    void listsAreDisjoint() {
        assertThat(ToolPermissionPolicy.silentlyAllowed())
                .doesNotContainAnyElementsOf(ToolPermissionPolicy.explicitAsk());
    }

    @Test
    @DisplayName("未知工具名 fail-closed：一律需要确认")
    void unknownToolsRequireConfirm() {
        // MCP 动态工具不在任何清单里，必须默认弹确认而不是默认放行
        assertThat(ToolPermissionPolicy.requiresConfirm("mcp__weird__do_something")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm("")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm("   ")).isTrue();
        assertThat(ToolPermissionPolicy.requiresConfirm(null)).isTrue();
    }

    @Test
    @DisplayName("requiresConfirm 与静默清单严格互补（前端过滤的正确性依赖此性质）")
    void requiresConfirmIsExactComplementOfSilentList() {
        Set<String> silent = ToolPermissionPolicy.silentlyAllowed();
        // 取一批覆盖各分组的真实工具名，逐个核对语义
        Set<String> sample = Set.of(
                "read_file", "grep_files", "list_directory", "analyze_code", "format_code",
                "write_file", "edit_file", "execute", "memory_save", "run_python",
                "agent_spawn", "session_list", "run_skill_script");
        for (String tool : sample) {
            assertThat(ToolPermissionPolicy.requiresConfirm(tool))
                    .as("工具 %s 的确认判定必须等于「不在静默清单中」", tool)
                    .isEqualTo(!silent.contains(tool));
        }
    }

    @Test
    @DisplayName("WorkspaceAgentBuilder 不再自持只读清单副本（防止与策略类漂移）")
    void builderKeepsNoPrivateCopyOfReadOnlyList() throws Exception {
        // 历史上 WorkspaceAgentBuilder 里有 READ_TOOLS / WRITE_TOOLS 两份私有常量，
        // 与前端展示用的清单各写一份必然漂移。已统一到 ToolPermissionPolicy，
        // 这里断言那两个字段确实不存在了 —— 有人复活它们就会红。
        Set<String> fieldNames = Set.of("READ_TOOLS", "WRITE_TOOLS");
        for (Field f : WorkspaceAgentBuilder.class.getDeclaredFields()) {
            assertThat(fieldNames)
                    .as("WorkspaceAgentBuilder 不应重新持有 %s，请改用 ToolPermissionPolicy", f.getName())
                    .doesNotContain(f.getName());
        }
    }

    @Test
    @DisplayName("format_code 归静默放行：它只处理传入文本，不写文件")
    void formatCodeIsReadOnlyBecauseItDoesNotTouchDisk() {
        // 这条单独立用例是因为 CapabilityTier 把 format_code 归在 WRITE_TOOLS，
        // 容易让人误以为它会落盘。实际实现（CodeGenerationTools.formatCode）只对入参
        // 字符串做正则清理并返回，写回磁盘必须另走 edit_file（那一步会确认）。
        assertThat(ToolPermissionPolicy.requiresConfirm("format_code")).isFalse();
    }
}
