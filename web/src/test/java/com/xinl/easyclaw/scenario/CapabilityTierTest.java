package com.xinl.easyclaw.scenario;

import com.xinl.easyclaw.tool.service.ToolRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        // 自定义 @Tool 名单由反射真实扫描得出，不手工维护 —— 手工列表会随新增工具腐烂，
        // 让本该报警的拼写错误被"顺手加进排除名单"掩盖过去。
        Set<String> customTools = scanCustomToolNames();
        assertThat(customTools)
                .as("反射未扫到任何自定义 @Tool，排除逻辑失效，本用例将退化为假绿")
                .isNotEmpty();

        for (String name : CapabilityTier.FULL.toolNames()) {
            if (customTools.contains(name)) {
                continue;
            }
            assertThat(ToolRegistryService.isFrameworkTool(name))
                    .as("档位工具名 [%s] 既不是框架内置工具也不是自定义 @Tool，白名单将永不匹配", name)
                    .isTrue();
        }
    }

    /**
     * 扫描本项目所有 @Tool 注解的工具名（与 Toolkit 实际注册来源保持一致）。
     * <p>
     * <b>持有类列表由 classpath 扫描得出，不手工枚举</b>：{@code AgentFactory} 注册的工具持有类
     * 都是 {@code com.xinl.easyclaw.tools} 包下的 {@code @Component}，这里按同样口径发现它们。
     * 早先这里硬编码了 4 个类，新增 {@code BlackboardTools} 后忘记同步，导致
     * {@code blackboard_append} 被误判成「不存在的工具名」——正是本方法注释想避免的腐烂。
     */
    private static Set<String> scanCustomToolNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Class<?> holder : discoverToolHolders()) {
            for (java.lang.reflect.Method m : holder.getMethods()) {
                io.agentscope.core.tool.Tool tool =
                        m.getAnnotation(io.agentscope.core.tool.Tool.class);
                if (tool != null && !tool.name().isBlank()) {
                    names.add(tool.name());
                }
            }
        }
        return names;
    }

    /**
     * 扫描 {@code com.xinl.easyclaw.tools} 包下所有 {@code @Component} 工具持有类。
     * <p>
     * 用 Spring 的 classpath 扫描器（不启动容器，仅做字节码扫描，开销可忽略），
     * 口径与 {@code AgentFactory} 注入 {@code @Component} 的方式一致：
     * 新增工具持有类后本用例自动覆盖，无需记得改测试。
     */
    private static List<Class<?>> discoverToolHolders() {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                org.springframework.stereotype.Component.class));
        List<Class<?>> holders = new java.util.ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.xinl.easyclaw.tools")) {
            try {
                holders.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到工具持有类但无法加载: " + bd.getBeanClassName(), e);
            }
        }
        return holders;
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
