package com.xinl.easyclaw.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceFileLayout} 单元测试。
 * <p>
 * 核心验收点是**幂等性与不覆盖用户数据** —— 这是把 {@code repair} 暴露为公开
 * 「修复」操作的前提。若模板生成会覆盖用户修改过的 AGENTS.md，那么每次修复
 * 都会静默丢掉用户的自定义规范。
 */
class WorkspaceFileLayoutTest {

    private WorkspaceFileLayout layout;

    @TempDir
    Path root;

    @BeforeEach
    void setUp() {
        layout = new WorkspaceFileLayout();
    }

    private Path agentDir() {
        return root.resolve(".easyClaw/agent");
    }

    @Test
    @DisplayName("initialize 建出完整目录骨架与两份模板文件")
    void initializeCreatesSkeleton() {
        layout.initialize(root, root.resolve(".easyClaw"));

        Path agent = agentDir();
        assertTrue(Files.isDirectory(agent.resolve("state")));
        assertTrue(Files.isDirectory(agent.resolve("skills")));
        assertTrue(Files.isDirectory(agent.resolve("subagents")));
        assertTrue(Files.isRegularFile(agent.resolve("AGENTS.md")));
        assertTrue(Files.isRegularFile(agent.resolve("MEMORY.md")));
    }

    @Test
    @DisplayName("repair 不覆盖用户已修改的模板文件（幂等契约的核心）")
    void repairPreservesUserEdits() throws IOException {
        layout.initialize(root, root.resolve(".easyClaw"));
        Path agents = agentDir().resolve("AGENTS.md");
        Files.writeString(agents, "# 我的自定义规范");

        layout.repair(agentDir());

        assertEquals("# 我的自定义规范", Files.readString(agents),
                "修复操作绝不能覆盖用户内容，否则每次修复都静默丢失自定义规范");
    }

    @Test
    @DisplayName("repair 能补回被用户误删的模板文件与目录")
    void repairRestoresDeletedFiles() throws IOException {
        layout.initialize(root, root.resolve(".easyClaw"));
        Files.delete(agentDir().resolve("MEMORY.md"));
        Files.delete(agentDir().resolve("skills"));

        layout.repair(agentDir());

        assertTrue(Files.isRegularFile(agentDir().resolve("MEMORY.md")));
        assertTrue(Files.isDirectory(agentDir().resolve("skills")));
    }

    @Test
    @DisplayName("initialize 可重复调用且结果稳定（幂等）")
    void initializeIsIdempotent() throws IOException {
        layout.initialize(root, root.resolve(".easyClaw"));
        String first = Files.readString(agentDir().resolve("AGENTS.md"));

        layout.initialize(root, root.resolve(".easyClaw"));

        assertEquals(first, Files.readString(agentDir().resolve("AGENTS.md")));
    }

    @Test
    @DisplayName("根目录遗留的 AGENTS.md / MEMORY.md 被迁移进 .easyClaw/agent")
    void migratesLegacyRootFiles() throws IOException {
        Files.writeString(root.resolve("AGENTS.md"), "旧版规范");
        Files.writeString(root.resolve("MEMORY.md"), "旧版记忆");

        layout.initialize(root, root.resolve(".easyClaw"));

        assertEquals("旧版规范", Files.readString(agentDir().resolve("AGENTS.md")),
                "迁移必须保留原内容，不能被模板顶掉");
        assertEquals("旧版记忆", Files.readString(agentDir().resolve("MEMORY.md")));
        assertFalse(Files.exists(root.resolve("AGENTS.md")), "源文件应已移走");
    }

    @Test
    @DisplayName("根目录遗留的 skills / subagents 目录连内容一起迁移")
    void migratesLegacyRootDirs() throws IOException {
        Files.createDirectories(root.resolve("skills/demo"));
        Files.writeString(root.resolve("skills/demo/SKILL.md"), "demo skill");
        Files.createDirectories(root.resolve("subagents"));
        Files.writeString(root.resolve("subagents/custom.md"), "custom agent");

        layout.initialize(root, root.resolve(".easyClaw"));

        assertEquals("demo skill",
                Files.readString(agentDir().resolve("skills/demo/SKILL.md")));
        assertEquals("custom agent",
                Files.readString(agentDir().resolve("subagents/custom.md")));
    }

    @Test
    @DisplayName("旧 .agentscope 目录整体迁入 .easyClaw/agent（保留对话历史）")
    void migratesLegacyAgentscopeDir() throws IOException {
        Files.createDirectories(root.resolve(".agentscope/state"));
        Files.writeString(root.resolve(".agentscope/state/s1.json"), "{}");

        layout.initialize(root, root.resolve(".easyClaw"));

        assertTrue(Files.isRegularFile(agentDir().resolve("state/s1.json")),
                "会话状态文件丢失等于用户对话历史丢失");
        assertFalse(Files.exists(root.resolve(".agentscope")), "迁移后旧目录应被清理");
    }

    @Test
    @DisplayName(".easyClaw 已存在时不再迁移 .agentscope，避免覆盖现役状态")
    void skipsAgentscopeMigrationWhenEasyClawExists() throws IOException {
        Files.createDirectories(agentDir().resolve("state"));
        Files.writeString(agentDir().resolve("state/current.json"), "current");
        Files.createDirectories(root.resolve(".agentscope/state"));
        Files.writeString(root.resolve(".agentscope/state/old.json"), "old");

        layout.initialize(root, root.resolve(".easyClaw"));

        assertEquals("current", Files.readString(agentDir().resolve("state/current.json")));
        assertFalse(Files.exists(agentDir().resolve("state/old.json")),
                "已有 .easyClaw 时不应把旧状态混进来");
    }

    @Test
    @DisplayName("harness 遗留的 local/ 与 default-user/ 目录被清理")
    void cleansLegacyHarnessDirs() throws IOException {
        Files.createDirectories(root.resolve("local/agents"));
        Files.writeString(root.resolve("local/agents/a.json"), "{}");
        Files.createDirectories(root.resolve("default-user/sessions"));

        layout.initialize(root, root.resolve(".easyClaw"));

        assertFalse(Files.exists(root.resolve("local")));
        assertFalse(Files.exists(root.resolve("default-user")));
    }

    @Test
    @DisplayName("deleteRecursively 删除整棵目录树；对不存在路径安全返回")
    void deleteRecursivelyRemovesTree() throws IOException {
        Path tree = root.resolve("a/b/c");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("f.txt"), "x");

        layout.deleteRecursively(root.resolve("a"));
        assertFalse(Files.exists(root.resolve("a")));

        layout.deleteRecursively(root.resolve("never-existed"));
    }

    @Test
    @DisplayName("路径不可用时 initialize 抛异常，避免带着半成品工作区继续走")
    void initializeFailsFastOnUnusablePath() throws IOException {
        // 用一个「文件」冒充目录：createDirectories 必然失败
        Path file = root.resolve("blocker");
        Files.writeString(file, "not a dir");

        assertThrows(IllegalStateException.class,
                () -> layout.initialize(file, file.resolve(".easyClaw")));
    }

    @Test
    @DisplayName("repair 遇到不可用路径只记警告，不影响对话主流程")
    void repairSwallowsIoFailure() throws IOException {
        Path file = root.resolve("blocker2");
        Files.writeString(file, "not a dir");

        // 不抛异常即为通过：模板缺失不该阻断对话
        layout.repair(file.resolve("agent"));
    }
}
