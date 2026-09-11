package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景绑定对子 Agent 的<b>硬隔离</b>测试。
 * <p>
 * 语义：子 Agent 的有效 skill = 声明自身 skills ∩ 场景绑定 skills。
 * 取交集而非覆盖 —— 两个限制都有存在理由，谁都不该被单方面绕过。
 * <p>
 * <b>本类为什么在 SPI 改造后仍然存在</b>：子 Agent 的来源已从
 * {@code ~/.easyClaw/subagents/*.md} 换成 SPI 注册表，但 skill 裁剪逻辑
 * （{@code SubagentLoader.restrictSkills}）被 SPI 路径原样复用。它的失效方式
 * 全部是<b>静默放权或静默失能</b>：交集算错只会让子 Agent 多几个或少几个 skill，
 * 既不报错也不影响启动。因此必须真的 load 一遍去数。
 * <p>
 * <b>断言目标为什么挑 researcher</b>：SPI 侧 7 个内置智能体里只有
 * {@code planner} 与 {@code researcher} 声明了具体 SkillPolicy，其余 5 个是
 * {@code SkillPolicy.UNRESTRICTED}。researcher 的声明最短
 * （backend-architecture / frontend-quality / devops-cicd），
 * 用它做交集断言时「哪一项该留、哪一项该被剔除」一眼可验。
 */
class SubagentScenarioIsolationTest {

    /** researcher 在 SPI 侧声明了具体 skills，断言以它为基准 —— 改了那边必须同步改这里 */
    private static final String RESEARCHER = "researcher";

    /** UNRESTRICTED（等价于未声明 skills）的代表，用于验证「直接采用场景绑定」分支 */
    private static final String CODER = "coder";

    private SubagentLoader newLoader() {
        // 本类只关心 skill 裁剪，人格恒走 SPI 内置，不再引入 DB 角色变量
        return new SubagentLoader(new AgentScopeProperties(), new AgentRegistry());
    }

    private ScenarioBinding bindSkills(String skillsJson) {
        ScenarioEntity e = new ScenarioEntity();
        e.setSkills(skillsJson);
        return ScenarioBinding.from(e);
    }

    /** 按名取出某个内置子 Agent 的声明；取不到直接失败，避免断言在空集上假通过 */
    private SubagentDeclaration load(String name, ScenarioBinding binding) {
        List<SubagentDeclaration> decls = newLoader().loadMerged(binding);
        assertThat(decls)
                .as("SPI 装配失败时列表为空，后续断言会在空集上假通过")
                .isNotEmpty();
        return decls.stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SPI 未装配出子 Agent: " + name));
    }

    @Test
    void 声明的skills应被场景绑定收窄为交集() {
        SubagentDeclaration decl = load(RESEARCHER,
                bindSkills("[\"backend-architecture\",\"devops-cicd\"]"));

        // frontend-quality 虽在 researcher 声明内，但未被场景绑定 → 必须被剔除
        assertThat(decl.getSkills()).containsExactly("backend-architecture", "devops-cicd");
        assertThat(decl.getSkills()).doesNotContain("frontend-quality");
    }

    @Test
    void 声明未写skills时应直接采用场景绑定() {
        // coder 的 SkillPolicy 是 UNRESTRICTED（空列表 = 不限制）
        SubagentDeclaration decl = load(CODER, bindSkills("[\"clean-code\"]"));

        // 原本是「不限制」，被场景收成「只有 clean-code」
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }

    @Test
    void 场景无skill绑定时应保留声明原值() {
        SubagentDeclaration decl = load(RESEARCHER, ScenarioBinding.EMPTY);

        assertThat(decl.getSkills())
                .containsExactly("backend-architecture", "frontend-quality", "devops-cicd");
    }

    @Test
    void 交集为空时应回退场景绑定而非禁掉全部skill() {
        // cursor-rules 是 researcher 明确排除的 skill，据此构造「声明 ∩ 绑定 = ∅」
        SubagentDeclaration decl = load(RESEARCHER, bindSkills("[\"cursor-rules\"]"));

        // 若返回空集，harness 的 SkillFilter.only(空) 会让该子 Agent 完全没有 skill；
        // 「配置写错」不该升级成「子 Agent 不可用」
        assertThat(decl.getSkills()).containsExactly("cursor-rules");
    }

    @Test
    void 交集匹配应忽略大小写() {
        SubagentDeclaration decl = load(RESEARCHER, bindSkills("[\"Backend-Architecture\"]"));

        // 另两项未绑定必须被剔除（保证本用例真的在检验交集，而非恰好返回原值）；
        // 绑定侧大小写不同但应匹配成功，且结果保留【声明侧】原始大小写 ——
        // harness 的 applySkillFilter 按 skill.getName() 精确匹配，
        // 若回吐绑定侧的 "Backend-Architecture"，运行期会静默匹配不到而丢光 skill
        assertThat(decl.getSkills()).containsExactly("backend-architecture");
    }

    @Test
    void 隔离不应波及tools声明() {
        // 只绑定 skills、不配 capabilityTier / MCP → hasToolBinding() 为 false，
        // 工具白名单必须原样保留。skill 裁剪若串到 tools 上，子 Agent 会连 read_file 都失去。
        SubagentDeclaration decl = load(CODER, bindSkills("[\"clean-code\"]"));

        assertThat(decl.getTools())
                .contains("execute", "read_file", "write_file", "edit_file",
                        "blackboard_append", "blackboard_read");
        assertThat(decl.getSkills()).containsExactly("clean-code");
    }
}
