package com.xinl.easyclaw.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.xinl.easyclaw.python.PythonSandbox;
import com.xinl.easyclaw.tools.SkillScriptTools;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端：Skills 页面「创建 .py 子文件」→「试跑脚本」这条链路。
 *
 * <p>{@link SkillChildPathTest} 只锁住写入侧的落点规则，
 * {@link com.xinl.easyclaw.tools.SkillScriptToolsTest} 只锁住执行侧的查找规则；
 * 两边各自绿灯并不能保证它们对「脚本该放哪」的约定一致——旧缺陷正是这样漏过去的
 * （创建时被补成 {@code check.py.md}，执行侧却在 {@code scripts/*.py} 里找）。
 *
 * <p>因此这里刻意不硬编码 {@code scripts/check.py}：落点完全由
 * {@link ManageController#resolveChildPath} 决定，再交给
 * {@link SkillScriptTools#runSkillScript} 去找。只要两侧约定错位，用例就红。
 */
class SkillScriptCreateRunFlowTest {

    private static PythonSandbox sandbox;
    private static SkillScriptTools tools;
    private static boolean ready;

    @BeforeAll
    static void setUp() {
        sandbox = new PythonSandbox();
        tools = new SkillScriptTools(sandbox);
        ready = sandbox.isAvailable();
    }

    /** 造一个工作区风格的 skill 根：<workspace>/.easyClaw/agent/skills/<name>/ */
    private Path makeSkillDir(Path wsRoot, String name) throws Exception {
        Path dir = wsRoot.resolve(".easyClaw/agent/skills").resolve(name);
        Files.createDirectories(dir);
        // run_skill_script 以 SKILL.md 存在作为「这是个 skill」的判据
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---\n");
        return dir;
    }

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("test-ws").path(root).build();
    }

    /** 模拟页面创建子文件：落点由生产代码决定，测试只负责按它写盘。 */
    private Path createChildAsPageDoes(Path skillDir, String rawName, String content)
            throws Exception {
        Path target = ManageController.resolveChildPath(skillDir, rawName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }

    @Test
    @DisplayName("页面创建的 check.py 随后能被 run_skill_script 找到并执行成功")
    void createdPythonChildIsRunnable(@TempDir Path root) throws Exception {
        assumeTrue(ready, "需要 GraalVM");

        Path skillDir = makeSkillDir(root, "flow-skill");

        // 脚本以 eval 方式执行（没有 __file__），工作目录是 skill 根目录
        Path created = createChildAsPageDoes(skillDir, "check.py", """
                import sys
                print('FLOW_MARKER_OK ' + ' '.join(sys.argv[1:]))
                print('CWD_SKILL_MD=' + open('SKILL.md').read().strip().replace('\\n', '|'))
                """);

        assertTrue(Files.isRegularFile(created), "创建侧未真正写盘: " + created);
        assertTrue(created.startsWith(skillDir.toAbsolutePath().normalize()),
                "子文件被写到 skill 目录之外: " + created);

        // 执行侧只拿到文件名，路径由它自己按 scripts/ 约定拼——这正是要验的契约
        String scriptName = created.getFileName().toString();
        String out = tools.runSkillScript("flow-skill", scriptName, List.of("from-page"), ws(root));

        assertFalse(out.contains("❌"), "创建后应可直接试跑，实际失败: " + out);
        assertTrue(out.startsWith("✅"), "应执行成功: " + out);
        assertTrue(out.contains("FLOW_MARKER_OK from-page"), "脚本 stdout 未回传: " + out);
        assertTrue(out.contains("CWD_SKILL_MD=---"), "工作目录应是 skill 根目录: " + out);
    }

    @Test
    @DisplayName("同一 skill 里的 md 子规则不会被当脚本执行")
    void createdMarkdownChildIsNotExecutable(@TempDir Path root) throws Exception {
        assumeTrue(ready, "需要 GraalVM");

        Path skillDir = makeSkillDir(root, "flow-skill");
        // 先建一个 .py，保证 scripts/ 目录存在——否则会先撞上「没有 scripts/ 目录」，
        // 断言就测不到真正想验的「非 .py 不予执行」
        createChildAsPageDoes(skillDir, "check.py", "print('ok')\n");

        Path mdChild = createChildAsPageDoes(skillDir, "components", "# 子规则\nprint('X')\n");
        assertTrue(mdChild.getFileName().toString().endsWith(".md"),
                "md 子规则落点变了: " + mdChild);
        assertTrue(mdChild.getParent().equals(skillDir.toAbsolutePath().normalize()),
                "md 子规则应留在 skill 根，不进 scripts/: " + mdChild);

        String out = tools.runSkillScript(
                "flow-skill", mdChild.getFileName().toString(), null, ws(root));

        assertTrue(out.contains("❌"), "md 子规则不该被执行: " + out);
        assertTrue(out.contains("只能运行 .py") || out.contains("脚本不存在"),
                "应明确拒绝而非静默失败: " + out);
        assertFalse(out.contains("✅"), "md 子规则竟被当脚本跑成功了: " + out);
    }
}
