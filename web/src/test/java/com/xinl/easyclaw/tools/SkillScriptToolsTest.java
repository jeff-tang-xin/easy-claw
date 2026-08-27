package com.xinl.easyclaw.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.xinl.easyclaw.python.PythonSandbox;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** run_skill_script 的解析、越界防护与执行行为。 */
class SkillScriptToolsTest {

    private static PythonSandbox sandbox;
    private static SkillScriptTools tools;
    private static boolean ready;

    @BeforeAll
    static void setUp() {
        sandbox = new PythonSandbox();
        tools = new SkillScriptTools(sandbox);
        ready = sandbox.isAvailable();
    }

    /** 造一个 workspace 内的 skill：.easyClaw/agent/skills/<name>/ */
    private Path makeSkill(Path wsRoot, String name) throws Exception {
        Path dir = wsRoot.resolve(".easyClaw/agent/skills").resolve(name);
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---\n");
        return dir;
    }

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("test-ws").path(root).build();
    }

    @Test
    @DisplayName("正常执行：脚本输出被回传，参数可用")
    void runsScriptWithArgs(@TempDir Path root) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path skill = makeSkill(root, "demo-skill");
        Files.writeString(skill.resolve("scripts/hello.py"),
                "import sys\nprint('HELLO ' + ' '.join(sys.argv[1:]))\n");

        String out = tools.runSkillScript("demo-skill", "hello.py", List.of("world"), ws(root));
        assertTrue(out.startsWith("✅"), "应执行成功: " + out);
        assertTrue(out.contains("HELLO world"), "输出未回传: " + out);
    }

    @Test
    @DisplayName("路径穿越：../ 被拒绝")
    void rejectsPathTraversal(@TempDir Path root) throws Exception {
        makeSkill(root, "demo-skill");
        String out = tools.runSkillScript("demo-skill", "../../../SKILL.md", null, ws(root));
        assertTrue(out.contains("越界") || out.contains("只能运行"), "必须拒绝穿越: " + out);
    }

    @Test
    @DisplayName("非 .py 文件不予执行")
    void rejectsNonPython(@TempDir Path root) throws Exception {
        Path skill = makeSkill(root, "demo-skill");
        Files.writeString(skill.resolve("scripts/run.sh"), "echo hi\n");
        String out = tools.runSkillScript("demo-skill", "run.sh", null, ws(root));
        assertTrue(out.contains("只能运行 .py"), "应拒绝非 py: " + out);
    }

    @Test
    @DisplayName("skill 不存在时列出可用 skill 帮助纠错")
    void unknownSkillListsCandidates(@TempDir Path root) throws Exception {
        makeSkill(root, "real-skill");
        String out = tools.runSkillScript("no-such-skill", "x.py", null, ws(root));
        assertTrue(out.contains("未找到 Skill"), out);
        assertTrue(out.contains("real-skill"), "应提示可用 skill: " + out);
    }

    @Test
    @DisplayName("脚本不存在时列出该 skill 的可用脚本")
    void unknownScriptListsCandidates(@TempDir Path root) throws Exception {
        Path skill = makeSkill(root, "demo-skill");
        Files.writeString(skill.resolve("scripts/a.py"), "print(1)\n");
        String out = tools.runSkillScript("demo-skill", "b.py", null, ws(root));
        assertTrue(out.contains("脚本不存在"), out);
        assertTrue(out.contains("a.py"), "应列出已有脚本: " + out);
    }

    @Test
    @DisplayName("没有 scripts/ 目录时给出明确说明")
    void noScriptsDir(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".easyClaw/agent/skills/plain");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "x");
        String out = tools.runSkillScript("plain", "x.py", null, ws(root));
        assertTrue(out.contains("没有 scripts/ 目录"), out);
    }

    @Test
    @DisplayName("脚本读不到 skill 目录之外的文件")
    void scriptCannotEscapeSkillDir(@TempDir Path root) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path skill = makeSkill(root, "demo-skill");
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "WS_SECRET");
        Files.writeString(skill.resolve("scripts/peek.py"),
                "try:\n    print(open(r'" + secret.toAbsolutePath() + "').read())\n"
                        + "except Exception:\n    print('DENIED')\n");
        String out = tools.runSkillScript("demo-skill", "peek.py", null, ws(root));
        assertFalse(out.contains("WS_SECRET"), "不应读到 skill 目录外的文件: " + out);
    }

    @Test
    @DisplayName("参数缺失时不抛异常，返回可读提示")
    void handlesBlankInput(@TempDir Path root) {
        assertTrue(tools.runSkillScript(null, "x.py", null, ws(root)).contains("未指定 skill"));
        assertTrue(tools.runSkillScript("s", "  ", null, ws(root)).contains("未指定脚本"));
    }
}
