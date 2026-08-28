package com.xinl.easyclaw.scenario;

import com.xinl.easyclaw.tool.service.ToolRegistryService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CapabilityTier} 档位语义与工具名正确性测试。
 * <p>
 * 最关键的一组是「与 ToolRegistryService 真实常量交叉校验」：档位里若写错工具名
 * （拼写错误、框架改名），白名单会静默永不匹配 —— 表现为子智能体莫名少工具，
 * 极难排查。这里用真实常量做断言，把这类错误拦在编译/测试期。
 */
class CapabilityTierTest {

    @Test
    void 档位应逐级包含形成严格子集链() {
        Set<String> readonly = CapabilityTier.READONLY.toolNames();
        Set<String> standard = CapabilityTier.STANDARD.toolNames();
        Set<String> full = CapabilityTier.FULL.toolNames();

        assertThat(CapabilityTier.NONE.toolNames()).isEmpty();
        assertThat(standard).containsAll(readonly);
        assertThat(full).containsAll(standard);
        // 逐级必须真的更宽，否则档位设计没有意义
        assertThat(standard).hasSizeGreaterThan(readonly.size());
        assertThat(full).hasSizeGreaterThan(standard.size());
    }

    @Test
    void READONLY不得包含任何写操作或Shell工具() {
        Set<String> readonly = CapabilityTier.READONLY.toolNames();

        assertThat(readonly)
                .doesNotContain("write_file")
                .doesNotContain("edit_file")
                .doesNotContain("execute");
        assertThat(readonly).contains("read_file", "grep_files");
    }

    @Test
    void STANDARD应含读写与skill脚本但不含Shell和子Agent调度() {
        Set<String> standard = CapabilityTier.STANDARD.toolNames();

        assertThat(standard).contains("read_file", "write_file", "edit_file", "run_skill_script");
        assertThat(standard).doesNotContain("execute", "agent_spawn");
    }

    @Test
    void FULL应含Shell与子Agent调度工具() {
        assertThat(CapabilityTier.FULL.toolNames())
                .contains("execute", "agent_spawn", "agent_send", "task_output");
    }

    @Test
    void 档位中的框架工具名必须真实存在于ToolRegistry() {
        // 反向校验：凡属于框架分组范畴的名字，必须能被 isFrameworkTool 认出。
        // 自定义 @Tool（run_skill_script/run_python）不在框架分组内，单独排除。
        Set<String> customTools = Set.of("run_skill_script", "run_python");

        for (String name : CapabilityTier.FULL.toolNames()) {
            if (customTools.contains(name)) {
                continue;
            }
            assertThat(ToolRegistryService.isFrameworkTool(name))
                    .as("档位工具名 [%s] 不是框架内置工具，白名单将永不匹配", name)
                    .isTrue();
        }
    }

    @Test
    void 解析档位名应大小写不敏感且非法值回退默认() {
        assertThat(CapabilityTier.parse("readonly", CapabilityTier.STANDARD))
                .isEqualTo(CapabilityTier.READONLY);
        assertThat(CapabilityTier.parse("FULL", CapabilityTier.STANDARD))
                .isEqualTo(CapabilityTier.FULL);
        assertThat(CapabilityTier.parse("  Standard  ", CapabilityTier.NONE))
                .isEqualTo(CapabilityTier.STANDARD);
        // 错字/空值一律回退，不抛异常（配置错误不应打断工作区加载）
        assertThat(CapabilityTier.parse("readonlyy", CapabilityTier.STANDARD))
                .isEqualTo(CapabilityTier.STANDARD);
        assertThat(CapabilityTier.parse(null, CapabilityTier.READONLY))
                .isEqualTo(CapabilityTier.READONLY);
        assertThat(CapabilityTier.parse("", CapabilityTier.READONLY))
                .isEqualTo(CapabilityTier.READONLY);
    }
}
