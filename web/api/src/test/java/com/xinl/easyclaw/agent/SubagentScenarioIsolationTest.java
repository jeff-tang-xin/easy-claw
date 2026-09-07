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
 * 场景绑定对子 Agent 的<b>硬隔离</b>测试。
 * <p>
 * 语义：子 Agent 的有效 skill = 声明自身 skills ∩ 场景绑定 skills。
 * 取交集而非覆盖 —— 两个限制都有存在理由，谁都不该被单方面绕过。
 * <p>
 * 注意本类<b>不</b>断言 tools：MCP 硬隔离在父 toolkit 注册阶段完成，
 * 若在声明里再塞工具白名单，harness 的 {@code allowlistedInheritedToolkit}
 * 会把不在名单里的 {@code read_file}/{@code execute} 等基础工具一并删掉。
 */
class SubagentScenarioIsolationTest {

    private SubagentLoader newLoader() {
        return new SubagentLoader(mock(RoleManagementService.class), new AgentScopeProperties());
    }

    private ScenarioBinding bindSkills(String skillsJson) {
        ScenarioEntity e = new ScenarioEntity();
        e.setSkills(skillsJson);
        return ScenarioBinding.from(e);
    }

    private SubagentDeclaration loadSingle(Path dir, String fileName, String content,
                                           ScenarioBinding binding) throws IOException {
        Files.writeString(dir.resolve(fileName), content);
        List<SubagentDeclaration> decls = newLoader().loadFromDirectory(dir, binding);
        assertThat(decls).hasSize(1);
        return decls.get(0);
    }

    @Test
    void 声明的skills应被场景绑定收窄为交集(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "reviewer.md", """
                ---
                description: 评审
                skills: [clean-code, code-refactor, devops-cicd]
                ---
                正文。
                """, bindSkills("[\"clean-code\",\"devops-cicd\"]"));

        // code-refactor 未被场景绑定 → 必须被剔除
        assertThat(decl.getSkills()).containsExactly("clean-code", "devops-cicd");
        assertThat(decl.getSkills()).doesNotContain("code-refactor");
    }

    @Test
    void 声明未写skills时应直接采用场景绑定(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                ---
                正文。
                """, bindSkills("[\"clean-code\"]"));

        // 原本是「不限制」，被场景收成「只有 clean-code」
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }

    @Test
    void 场景无skill绑定时应保留声明原值(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                skills: [clean-code, code-refactor]
                ---
                正文。
                """, ScenarioBinding.EMPTY);

        assertThat(decl.getSkills()).containsExactly("clean-code", "code-refactor");
    }

    @Test
    void 交集为空时应回退场景绑定而非禁掉全部skill(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                skills: [frontend-quality]
                ---
                正文。
                """, bindSkills("[\"clean-code\"]"));

        // 若返回空集，harness 的 SkillFilter.only(空) 会让该子 Agent 完全没有 skill；
        // 「配置写错」不该升级成「子 Agent 不可用」
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }

    @Test
    void 交集匹配应忽略大小写(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                skills: [Clean-Code, devops-cicd]
                ---
                正文。
                """, bindSkills("[\"clean-code\"]"));

        // devops-cicd 未绑定必须被剔除（保证本用例真的在检验交集，而非恰好返回原值）；
        // Clean-Code 与绑定值大小写不同但应匹配成功，且保留声明侧原始大小写
        assertThat(decl.getSkills()).containsExactly("Clean-Code");
    }

    @Test
    void 隔离不应波及tools声明(@TempDir Path dir) throws IOException {
        SubagentDeclaration decl = loadSingle(dir, "coder.md", """
                ---
                description: 实现
                tools: [read_file, grep_files]
                skills: [clean-code, code-refactor]
                ---
                正文。
                """, bindSkills("[\"clean-code\"]"));

        assertThat(decl.getTools())
                .containsExactly("read_file", "grep_files", "blackboard_append", "blackboard_read");
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }

    @Test
    void 无参重载应等价于不限制保持向后兼容(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("coder.md"), """
                ---
                description: 实现
                skills: [clean-code, code-refactor]
                ---
                正文。
                """);

        List<SubagentDeclaration> decls = newLoader().loadFromDirectory(dir);

        assertThat(decls).hasSize(1);
        assertThat(decls.get(0).getSkills()).containsExactly("clean-code", "code-refactor");
    }

    @Test
    void loadMerged应对两级目录都施加隔离(@TempDir Path root) throws IOException {
        Path global = Files.createDirectories(root.resolve("global"));
        Path ws = Files.createDirectories(root.resolve("ws"));
        Files.writeString(global.resolve("g.md"), """
                ---
                description: 全局
                skills: [clean-code, devops-cicd]
                ---
                正文。
                """);
        Files.writeString(ws.resolve("w.md"), """
                ---
                description: 工作区
                skills: [clean-code, frontend-quality]
                ---
                正文。
                """);

        List<SubagentDeclaration> decls = newLoader()
                .loadMerged(global, ws, bindSkills("[\"clean-code\"]"));

        assertThat(decls).hasSize(2);
        assertThat(decls).allSatisfy(d ->
                assertThat(d.getSkills()).containsExactly("clean-code"));
    }

    @Test
    void binding传null时应视为不限制(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("coder.md"), """
                ---
                description: 实现
                skills: [clean-code, code-refactor]
                ---
                正文。
                """);

        List<SubagentDeclaration> decls = newLoader().loadMerged(null, dir, null);

        assertThat(decls).hasSize(1);
        assertThat(decls.get(0).getSkills()).containsExactly("clean-code", "code-refactor");
    }
}
