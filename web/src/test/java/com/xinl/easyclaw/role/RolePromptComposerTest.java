package com.xinl.easyclaw.role;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RolePromptComposer} 单元测试。
 * <p>
 * 覆盖"角色决定系统提示词"这条链路的入口行为。此前 role/goal/backstory
 * 三个字段在整个代码库中零消费，本测试锁定它们确实进入了 prompt。
 */
class RolePromptComposerTest {

    @Test
    @DisplayName("三要素齐全时渲染完整人格片段")
    void composesFullPersona() {
        AgentRoleEntity role = AgentRoleEntity.builder()
                .name("main")
                .displayName("AI-CLAW")
                .role("全栈工程智能体")
                .goal("以最小必要改动达成用户意图")
                .backstory("你在真实工程环境中工作。")
                .build();

        String prompt = RolePromptComposer.compose(role);

        assertNotNull(prompt);
        assertTrue(prompt.contains("AI-CLAW"), "应包含角色展示名");
        assertTrue(prompt.contains("全栈工程智能体"), "应包含角色定位");
        assertTrue(prompt.contains("以最小必要改动达成用户意图"), "应包含目标");
        assertTrue(prompt.contains("你在真实工程环境中工作。"), "应包含背景故事");
    }

    @Test
    @DisplayName("角色为 null 时返回 null（无人格覆盖）")
    void returnsNullForNullRole() {
        assertNull(RolePromptComposer.compose(null));
    }

    @Test
    @DisplayName("仅配置模型、三要素全空的角色不产生人格片段")
    void returnsNullWhenPersonaEmpty() {
        AgentRoleEntity modelOnly = AgentRoleEntity.builder()
                .name("main")
                .displayName("AI-CLAW")
                .model("deepseek:deepseek-chat")
                .build();

        assertNull(RolePromptComposer.compose(modelOnly),
                "只用来指定模型的角色不应污染 system prompt");
    }

    @Test
    @DisplayName("部分字段缺失时只渲染已有字段，不出现空标题")
    void skipsBlankFields() {
        AgentRoleEntity partial = AgentRoleEntity.builder()
                .name("reviewer")
                .displayName("评审员")
                .role("代码评审专家")
                .goal("   ")
                .build();

        String prompt = RolePromptComposer.compose(partial);

        assertNotNull(prompt);
        assertTrue(prompt.contains("代码评审专家"));
        assertTrue(!prompt.contains("**你的目标**"), "空白目标不应渲染标题");
        assertTrue(!prompt.contains("**背景设定**"), "缺失背景不应渲染标题");
    }

    @Test
    @DisplayName("displayName 缺失时回退到英文标识名")
    void fallsBackToNameWhenDisplayNameMissing() {
        AgentRoleEntity noDisplay = AgentRoleEntity.builder()
                .name("code-expert")
                .role("架构师")
                .build();

        assertEquals("code-expert", RolePromptComposer.displayNameOf(noDisplay));
        assertTrue(RolePromptComposer.compose(noDisplay).contains("code-expert"));
    }
}
