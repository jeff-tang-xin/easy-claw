package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubagentLoader} 角色绑定行为测试。
 * <p>
 * 锁定「编排单位是角色，.md 只是执行外壳」这条设计：声明 frontmatter 的
 * {@code role:} 把 DB 角色（人格 + 模型）与磁盘声明（工具 + 步数）钉在一起。
 * 此前 {@code role:} 只被用来取 model，人格三要素被丢弃 —— 本测试锁定该缺口已补上。
 */
class SubagentLoaderTest {

    @TempDir
    Path dir;

    private SubagentLoader loader;

    /** 极简桩：只实现 findByName，其余方法本测试不触达。 */
    private static class StubRoleService implements RoleManagementService {
        private final java.util.Map<String, AgentRoleEntity> roles = new java.util.HashMap<>();

        void put(AgentRoleEntity role) {
            roles.put(role.getName(), role);
        }

        @Override
        public Optional<AgentRoleEntity> findByName(String name) {
            return Optional.ofNullable(roles.get(name));
        }

        @Override
        public AgentRoleEntity create(AgentRoleEntity role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRoleEntity update(Long id, AgentRoleEntity role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentRoleEntity> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRoleEntity> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRoleEntity> findActiveRoles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRoleEntity setActive(Long id, boolean active) {
            throw new UnsupportedOperationException();
        }
    }

    private StubRoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new StubRoleService();
        loader = new SubagentLoader(roleService, new AgentScopeProperties());
    }

    private void writeAgent(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name + ".md"), content);
    }

    private SubagentDeclaration loadOne() {
        List<SubagentDeclaration> list = loader.loadFromDirectory(dir);
        assertEquals(1, list.size(), "应恰好加载一个声明");
        return list.get(0);
    }

    @Test
    @DisplayName("绑定角色时：人格三要素注入提示词，且置于声明正文之前")
    void injectsRolePersonaBeforeBody() throws IOException {
        roleService.put(AgentRoleEntity.builder()
                .name("coder")
                .displayName("代码实现专家")
                .role("代码实现专家")
                .goal("按任务指令完成高质量代码实现")
                .backstory("你动手前先读懂上下文。")
                .model("")
                .active(true)
                .build());
        writeAgent("coder", """
                ---
                description: 代码实现专家
                role: coder
                ---
                # 工作方法
                先读代码再动手。
                """);

        String prompt = loadOne().getInlineAgentsBody();
        assertTrue(prompt.contains("代码实现专家"), "应含角色定位");
        assertTrue(prompt.contains("按任务指令完成高质量代码实现"), "应含角色目标");
        assertTrue(prompt.contains("你动手前先读懂上下文。"), "应含角色背景");
        assertTrue(prompt.contains("# 工作方法"), "应保留声明正文");
        assertTrue(prompt.indexOf("按任务指令完成高质量代码实现") < prompt.indexOf("# 工作方法"),
                "身份须先于工作方法确立");
    }

    @Test
    @DisplayName("未声明 role 时：提示词只有声明正文，不含人格段")
    void noRoleMeansNoPersona() throws IOException {
        writeAgent("plain", """
                ---
                description: 无角色声明
                ---
                只干活，不谈身份。
                """);

        String prompt = loadOne().getInlineAgentsBody();
        assertTrue(prompt.contains("只干活，不谈身份。"));
        assertTrue(!prompt.contains("你的角色"), "无绑定角色时不应出现人格段");
    }

    @Test
    @DisplayName("role 指向不存在的角色：降级为无角色，不抛异常")
    void unknownRoleDegradesGracefully() throws IOException {
        writeAgent("ghost", """
                ---
                description: 引用了不存在的角色
                role: nobody
                ---
                正文照常。
                """);

        String prompt = loadOne().getInlineAgentsBody();
        assertTrue(prompt.contains("正文照常。"));
        assertTrue(!prompt.contains("你的角色"), "角色缺失时按无人格处理");
    }

    @Test
    @DisplayName("模型：frontmatter 未写时取角色模型")
    void inheritsRoleModel() throws IOException {
        roleService.put(AgentRoleEntity.builder()
                .name("researcher").displayName("研究员")
                .role("研究员").model("qwen3-max").active(true).build());
        writeAgent("researcher", """
                ---
                description: 研究员
                role: researcher
                ---
                正文。
                """);

        assertEquals("qwen3-max", loadOne().getModel());
    }

    @Test
    @DisplayName("模型：frontmatter 显式指定时优先于角色模型")
    void frontmatterModelWinsOverRole() throws IOException {
        roleService.put(AgentRoleEntity.builder()
                .name("researcher").displayName("研究员")
                .role("研究员").model("qwen3-max").active(true).build());
        writeAgent("researcher", """
                ---
                description: 研究员
                role: researcher
                model: gpt-4o-mini
                ---
                正文。
                """);

        assertEquals("gpt-4o-mini", loadOne().getModel());
    }

    @Test
    @DisplayName("角色模型为空时不覆盖：保持 null 以继承父模型")
    void blankRoleModelLeavesInheritance() throws IOException {
        roleService.put(AgentRoleEntity.builder()
                .name("coder").displayName("代码实现专家")
                .role("代码实现专家").model("").active(true).build());
        writeAgent("coder", """
                ---
                description: 代码实现专家
                role: coder
                ---
                正文。
                """);

        assertNull(loadOne().getModel(), "空模型应保持未设置，由父模型兜底");
    }

    @Test
    @DisplayName("声明正文为空但绑定角色时：人格独立成为提示词")
    void personaSurvivesEmptyBody() throws IOException {
        roleService.put(AgentRoleEntity.builder()
                .name("planner").displayName("任务规划专家")
                .role("任务规划专家").goal("拆解复杂需求").active(true).build());
        writeAgent("planner", """
                ---
                description: 任务规划专家
                role: planner
                ---
                """);

        String prompt = loadOne().getInlineAgentsBody();
        assertNotNull(prompt);
        assertTrue(prompt.contains("拆解复杂需求"), "正文为空时人格仍须生效");
    }
}
