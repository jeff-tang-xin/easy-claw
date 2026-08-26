package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.config.seed.SystemDataSeeder.SeedMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置子 Agent 声明的版本化播种逻辑测试。
 * <p>
 * 背景：历史实现用 {@code if (Files.exists(file)) return;} 守卫播种，导致内置模板
 * 在代码里升级后，磁盘上的旧文件永不更新（"代码里有了，外面就不更新了"）。
 * 真实故障：工作区遗留的 reviewer.md 带 {@code steps: 8}，压制了全局声明，
 * 使子 Agent 迭代提前耗尽、回复被截断，表现为「子流程返回空」。
 * <p>
 * 核心不变量：**绝不覆盖用户改动过的文件**。判据为正文指纹与 seedHash 一致。
 */
class SubagentSeedVersionTest {

    @TempDir
    Path tempDir;

    private static final int CUR = SystemDataSeeder.SUBAGENT_SEED_VERSION;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** 构造一份「未被改动过」的旧版播种文件：seedHash 与正文真实指纹一致。 */
    private Path seededFile(String name, int version, String body) throws IOException {
        String hash = SystemDataSeeder.bodyHash(body);
        return write(name, "---\ndescription: d\nseedVersion: " + version
                + "\nseedHash: " + hash + "\n---\n\n" + body);
    }

    // ---------- 覆盖决策 ----------

    @Test
    @DisplayName("旧版本且正文未改动 → 允许升级")
    void pristineOlderFileIsUpgradable() throws IOException {
        Path f = seededFile("a.md", 1, "原始正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertEquals(1, meta.version());
        assertEquals(meta.hash(), meta.actualHash(), "未改动文件的指纹应与 seedHash 相符");
        assertTrue(SystemDataSeeder.shouldOverwrite(meta));
    }

    @Test
    @DisplayName("旧版本但用户改过正文 → 必须保留（阻塞项回归）")
    void userEditedOlderFileIsPreserved() throws IOException {
        // 关键场景：用户编辑了正文却保留了 seedVersion，旧实现会静默覆盖掉用户修改
        String hash = SystemDataSeeder.bodyHash("原始正文\n");
        Path f = write("b.md", "---\ndescription: d\nseedVersion: 1\nseedHash: " + hash
                + "\n---\n\n我改过的正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertFalse(SystemDataSeeder.shouldOverwrite(meta),
                "用户改动过的文件绝不能被内置模板覆盖");
    }

    @Test
    @DisplayName("无 seedVersion 标记 → 视为用户自定义，不覆盖")
    void unversionedFileIsPreserved() throws IOException {
        Path f = write("c.md", "---\ndescription: 我自己写的\nsteps: 8\n---\n\n正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertNull(meta.version());
        assertFalse(SystemDataSeeder.shouldOverwrite(meta));
    }

    @Test
    @DisplayName("旧版本但缺 seedHash → 无法证明未改动，保守保留")
    void olderFileWithoutHashIsPreserved() throws IOException {
        Path f = write("d.md", "---\ndescription: d\nseedVersion: 1\n---\n\n正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertNull(meta.hash());
        assertFalse(SystemDataSeeder.shouldOverwrite(meta));
    }

    @Test
    @DisplayName("已是当前版本 → 不重复写盘")
    void currentVersionIsNotRewritten() throws IOException {
        Path f = seededFile("e.md", CUR, "正文\n");
        assertFalse(SystemDataSeeder.shouldOverwrite(SystemDataSeeder.readSeedMeta(f)));
    }

    @Test
    @DisplayName("版本号高于当前 → 不回退覆盖")
    void newerVersionIsPreserved() throws IOException {
        Path f = seededFile("f.md", CUR + 1, "正文\n");
        assertFalse(SystemDataSeeder.shouldOverwrite(SystemDataSeeder.readSeedMeta(f)));
    }

    @Test
    @DisplayName("范围判断取代白名单：升版后无需登记，中间版本仍可刷新")
    void anyVersionInRangeIsUpgradable() throws IOException {
        // 旧实现用手工维护的 KNOWN_SEED_VERSIONS，漏登记会让该版本永久不再刷新
        for (int v = 1; v < CUR; v++) {
            Path f = seededFile("g" + v + ".md", v, "正文\n");
            assertTrue(SystemDataSeeder.shouldOverwrite(SystemDataSeeder.readSeedMeta(f)),
                    "v" + v + " 未改动，应可升级到 v" + CUR);
        }
    }

    @Test
    @DisplayName("非法/越界版本号不误判为可覆盖")
    void invalidVersionIsPreserved() {
        assertFalse(SystemDataSeeder.shouldOverwrite(new SeedMeta(0, "x", "x")));
        assertFalse(SystemDataSeeder.shouldOverwrite(new SeedMeta(-1, "x", "x")));
        assertFalse(SystemDataSeeder.shouldOverwrite(null));
    }

    // ---------- frontmatter 解析健壮性 ----------

    @Test
    @DisplayName("容忍额外空格、引号与行尾注释")
    void lenientVersionParsing() throws IOException {
        Path f = write("h.md", "---\nseedVersion :  \"2\"   # 升级标记\nseedHash: \"abcdef1234567890\"\n---\n\n正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertEquals(2, meta.version());
        assertEquals("abcdef1234567890", meta.hash());
    }

    @Test
    @DisplayName("剥离 BOM 后仍能识别 frontmatter")
    void bomIsStripped() throws IOException {
        Path f = write("i.md", "\uFEFF---\nseedVersion: 1\n---\n\n正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta, "带 BOM 的文件不应被当作无 frontmatter");
        assertEquals(1, meta.version());
    }

    @Test
    @DisplayName("CRLF 与 LF 的正文指纹一致，换行差异不算用户改动")
    void lineEndingsDoNotAffectHash() {
        assertEquals(SystemDataSeeder.bodyHash("a\nb\n"), SystemDataSeeder.bodyHash("a\r\nb\r\n"));
    }

    @Test
    @DisplayName("frontmatter 未闭合 → 结构不可信，视为用户自定义")
    void unclosedFrontmatterReturnsNull() throws IOException {
        assertNull(SystemDataSeeder.readSeedMeta(write("j.md", "---\nseedVersion: 1\n没有闭合\n")));
    }

    @Test
    @DisplayName("正文中的 seedVersion 行不被误读")
    void seedVersionInBodyIsIgnored() throws IOException {
        Path f = write("k.md", "---\ndescription: d\n---\n\n正文里写 seedVersion: 1 不算\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertNull(meta.version(), "frontmatter 结束后不应继续解析");
    }

    @Test
    @DisplayName("非 frontmatter 开头 / 文件缺失 → 返回 null 且不抛异常")
    void malformedInputsAreSafe() throws IOException {
        assertNull(SystemDataSeeder.readSeedMeta(write("l.md", "seedVersion: 2\n没有 --- 开头\n")));
        assertNull(SystemDataSeeder.readSeedMeta(tempDir.resolve("nope.md")));
    }

    @Test
    @DisplayName("seedVersion 值非法时按用户自定义处理")
    void malformedVersionIsSafe() throws IOException {
        Path f = write("m.md", "---\ndescription: d\nseedVersion: abc\n---\n\n正文\n");
        SeedMeta meta = SystemDataSeeder.readSeedMeta(f);
        assertNotNull(meta);
        assertNull(meta.version());
    }

    // ---------- 模板内容约束 ----------

    @Test
    @DisplayName("内置模板不再硬编码 steps，交由配置统一管控")
    void seededTemplateMustNotPinSteps() throws IOException {
        // 回归保护：一旦有人把 steps 写回播种模板，此测试失败
        Path seeder = Path.of("src/main/java/com/xinl/easyclaw/config/seed/SystemDataSeeder.java");
        String src = Files.readString(seeder, StandardCharsets.UTF_8);
        assertTrue(src.contains("\\nseedVersion: "), "播种内容应包含 seedVersion 标记");
        assertFalse(src.contains("\\nsteps: "),
                "播种模板不应硬编码 steps，应由 agentscope.agent.subagent-steps 决定");
    }
}
