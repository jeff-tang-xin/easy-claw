package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端：真实随包发布的 skill 脚本能在沙箱里跑出正确结果。
 *
 * <p>单测各组件都通过 ≠ 整条链路可用。这个用例直接拿 {@code resources/skills/} 下
 * 实际发布的脚本执行，任何一环（脚本语法、沙箱 IO、argv 传递）坏掉都会红。
 */
class SkillScriptEndToEndTest {

    private static PythonSandbox sandbox;
    private static boolean ready;

    @BeforeAll
    static void setUp() {
        sandbox = new PythonSandbox();
        ready = sandbox.isAvailable();
    }

    @Test
    @DisplayName("clean-code 的 smell_scan.py 能检出注入的坏味道")
    void smellScanDetectsInjectedSmells(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");

        Path shipped = Path.of("src/main/resources/skills/clean-code/scripts/smell_scan.py");
        assumeTrue(Files.exists(shipped), "脚本未随包发布: " + shipped.toAbsolutePath());

        // 沙箱只放开 dir，脚本与被扫描文件都要在里面
        Path script = dir.resolve("smell_scan.py");
        Files.copy(shipped, script);

        Path target = dir.resolve("bad.py");
        Files.writeString(target, """
                def f(a, b, c, d, e, g):   # TODO 参数太多
                    try:
                        return a
                    except:
                        pass
                """);

        PythonSandbox.Result r = sandbox.executeScript(script, dir, List.of("bad.py"), null);
        assertTrue(r.ok(), "脚本执行失败: " + r.error() + " / " + r.stderr());
        assertTrue(r.stdout().contains("参数过多"), "未检出参数过多: " + r.stdout());
        assertTrue(r.stdout().contains("裸 except"), "未检出裸 except: " + r.stdout());
        assertTrue(r.stdout().contains("TODO"), "未检出遗留标记: " + r.stdout());
    }

    @Test
    @DisplayName("干净文件不产生误报")
    void cleanFileHasNoFindings(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path shipped = Path.of("src/main/resources/skills/clean-code/scripts/smell_scan.py");
        assumeTrue(Files.exists(shipped), "脚本未随包发布");

        Path script = dir.resolve("smell_scan.py");
        Files.copy(shipped, script);
        Path target = dir.resolve("good.py");
        Files.writeString(target, "def add(a, b):\n    return a + b\n");

        PythonSandbox.Result r = sandbox.executeScript(script, dir, List.of("good.py"), null);
        assertTrue(r.ok(), "脚本执行失败: " + r.error());
        assertTrue(r.stdout().contains("未发现机械性坏味道"), "干净文件被误报: " + r.stdout());
    }

    @Test
    @DisplayName("无参数时打印用法而非崩溃")
    void printsUsageWithoutArgs(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path shipped = Path.of("src/main/resources/skills/clean-code/scripts/smell_scan.py");
        assumeTrue(Files.exists(shipped), "脚本未随包发布");
        Path script = dir.resolve("smell_scan.py");
        Files.copy(shipped, script);

        PythonSandbox.Result r = sandbox.executeScript(script, dir, null, null);
        assertTrue(r.stdout().contains("用法"), "应打印用法: " + r.stdout() + r.error());
    }
}
