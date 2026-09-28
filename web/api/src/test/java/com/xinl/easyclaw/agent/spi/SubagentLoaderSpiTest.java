package com.xinl.easyclaw.agent.spi;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubagentLoader} 接入 SPI 的主链路验证。
 * <p>
 * <b>这个测试类存在的理由</b>：SPI 装配失败的方式是<b>静默的</b>——
 * {@code agentRegistry} 为 null、ServiceLoader 找不到 services 文件、
 * 转换时抛异常被 catch 掉，三者都只会让子 Agent 列表少几项而不会报错，
 * 「编译通过 + 启动成功」完全无法证明 SPI 生效。因此必须真的 load 一遍数出来。
 */
class SubagentLoaderSpiTest {

    private SubagentLoader loaderWithSpi() {
        return new SubagentLoader(new AgentScopeProperties(), new AgentRegistry());
    }

    @Test
    @DisplayName("SPI 真的接进主链路：无 .md 目录时仍能装出 7 个内置子 Agent")
    void spiDeclarationsReachMainPath() {
        List<SubagentDeclaration> decls =
                loaderWithSpi().loadMerged(ScenarioBinding.EMPTY);

        assertThat(decls)
                .as("SPI 未生效时这里会是空列表——这正是改造前的状态")
                .isNotEmpty();
        assertThat(decls).extracting(SubagentDeclaration::getName)
                .containsExactlyInAnyOrder(
                        "coder", "reviewer", "planner",
                        "researcher", "code-expert", "file-expert", "ops");
    }

    @Test
    @DisplayName("main 不作为子 Agent 暴露，避免主控派遣自己造成递归")
    void mainAgentIsNotExposedAsSubagent() {
        List<SubagentDeclaration> decls =
                loaderWithSpi().loadMerged(ScenarioBinding.EMPTY);

        assertThat(decls).extracting(SubagentDeclaration::getName)
                .doesNotContain("main");
    }

    @Test
    @DisplayName("SPI 路径同样注入黑板段与交付纪律段，与 .md 路径行为一致")
    void spiPromptCarriesProgrammaticSections() {
        SubagentDeclaration coder = loaderWithSpi()
                .loadMerged(ScenarioBinding.EMPTY).stream()
                .filter(d -> "coder".equals(d.getName()))
                .findFirst().orElseThrow();

        assertThat(coder.getInlineAgentsBody())
                .as("黑板段缺失会让子 Agent 不知道要写黑板，截断时产出全部丢失")
                .contains("blackboard_append")
                .as("交付纪律段缺失会让子 Agent 交回半成品")
                .contains("交付");
    }

    @Test
    @DisplayName("工具别名归一化在 SPI 路径生效：shell/grep 必须已转为 execute/grep_files")
    void toolAliasesAreNormalizedOnSpiPath() {
        SubagentDeclaration coder = loaderWithSpi()
                .loadMerged(ScenarioBinding.EMPTY).stream()
                .filter(d -> "coder".equals(d.getName()))
                .findFirst().orElseThrow();

        // harness 的 allowlistedInheritedToolkit 按名严格相等裁剪，
        // 对不上的名字会被静默移除 —— 归一化失效表现为「工具凭空消失」。
        assertThat(coder.getTools())
                .contains("execute", "read_file", "write_file", "edit_file")
                .doesNotContain("shell", "bash", "grep", "glob");
    }

    @Test
    @DisplayName("步数不低于绝对下限 30，防止子 Agent 被步数耗尽截断")
    void stepFloorIsRespected() {
        List<SubagentDeclaration> decls =
                loaderWithSpi().loadMerged(ScenarioBinding.EMPTY);

        assertThat(decls).allSatisfy(d ->
                assertThat(d.getSteps()).isGreaterThanOrEqualTo(30));
    }

    @Test
    @DisplayName("未注入 AgentRegistry 时返回空列表：SPI 下线后已无 .md 兜底")
    void withoutRegistryYieldsNothing() {
        SubagentLoader legacy =
                new SubagentLoader(new AgentScopeProperties());

        // .md 链路整体下线后，SPI 是唯一来源。registry 缺失 = 一个子 Agent 都装不出来，
        // 这条断言的价值在于把「静默变空」钉成已知行为，而非留给运行期去发现。
        assertThat(legacy.loadMerged(ScenarioBinding.EMPTY)).isEmpty();
    }
}
