package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.role.service.RoleManagementService;
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
 * {@link SubagentLoader} 对 frontmatter {@code skills:} 字段的透传测试。
 * <p>
 * 背景：harness 的 {@code HarnessAgentBuilderSupport} 已实现
 * {@code SkillFilter.only(decl.getSkills())}，即子 Agent 的 skill 白名单能力
 * 在框架层是现成的；但我方 loader 此前只解析 {@code tools} 而漏掉 {@code skills}，
 * 导致用户在声明里写了 skills 也完全不生效（静默失效，无任何告警）。
 * <p>
 * 这些用例锁死解析行为，避免回归。
 */
class SubagentSkillsBindingTest {

    /** 造一个只依赖真实 properties 的 loader（role 查询在本测试中不触发） */
    private SubagentLoader newLoader() {
        AgentScopeProperties props = new AgentScopeProperties();
        return new SubagentLoader(mock(RoleManagementService.class), props);
    }

    private SubagentDeclaration loadSingle(Path dir, String fileName, String content)
            throws IOException {
        Files.writeString(dir.resolve(fileName), content);
        List<SubagentDeclaration> decls = newLoader().loadFromDirectory(dir);
        assertThat(decls).hasSize(1);
        return decls.get(0);
    }

    @Test
    void 数组形式的skills应被解析为白名单(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "reviewer.md", """
                ---
                description: 代码评审
                skills: [clean-code, code-refactor]
                ---
                你是评审专家。
                """);

        assertThat(decl.getSkills()).containsExactly("clean-code", "code-refactor");
    }

    @Test
    void 逗号分隔且带引号的skills应被正确清洗(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                skills: "backend-architecture", 'clean-code'
                ---
                你是实现专家。
                """);

        assertThat(decl.getSkills()).containsExactly("backend-architecture", "clean-code");
    }

    @Test
    void 未声明skills时应为空表示不限制(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "plain.md", """
                ---
                description: 无 skill 约束
                ---
                正文。
                """);

        // 空列表 = harness 侧不安装 SkillFilter = 继承全部 skill（向后兼容）
        assertThat(decl.getSkills()).isEmpty();
    }

    @Test
    void skills与tools应各自独立解析互不干扰(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "mixed.md", """
                ---
                description: 混合声明
                tools: [read_file, grep_files]
                skills: [clean-code]
                ---
                正文。
                """);

        assertThat(decl.getTools()).containsExactly("read_file", "grep_files");
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }

    @Test
    void 空值skills不应产生空字符串条目(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "blank.md", """
                ---
                description: 空 skills
                skills: []
                ---
                正文。
                """);

        assertThat(decl.getSkills()).isEmpty();
    }
}
