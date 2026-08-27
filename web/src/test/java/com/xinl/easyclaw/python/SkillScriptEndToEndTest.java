package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        PythonSandbox.Result r = sandbox.executeScriptReadOnly(script, dir, List.of("bad.py"), null);
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

        PythonSandbox.Result r = sandbox.executeScriptReadOnly(script, dir, List.of("good.py"), null);
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

        PythonSandbox.Result r = sandbox.executeScriptReadOnly(script, dir, null, null);
        assertTrue(r.stdout().contains("用法"), "应打印用法: " + r.stdout() + r.error());
    }

    @Test
    @DisplayName("smell_scan.py 能扫描 skill 目录之外的项目文件（只读根放行）")
    void smellScanReadsFileOutsideSkillDir(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path shipped = Path.of("src/main/resources/skills/clean-code/scripts/smell_scan.py");
        assumeTrue(Files.exists(shipped), "脚本未随包发布");

        // 还原真实拓扑：脚本在 skill 目录里，被扫描的代码在完全独立的工作区目录里。
        // 这是 run_skill_script 的实际用法，也是只放开 skill 目录时必然失败的场景。
        Path skillDir = Files.createDirectories(dir.resolve("skill"));
        Files.writeString(skillDir.resolve("SKILL.md"), "# rules");
        Path script = skillDir.resolve("smell_scan.py");
        Files.copy(shipped, script);

        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path target = workspace.resolve("bad.py");
        Files.writeString(target, """
                def f(a, b, c, d, e, g):   # TODO 参数太多
                    try:
                        return a
                    except:
                        pass
                """);

        ScriptAccess access = ScriptAccess.readOnly(skillDir).plusReadable(workspace);
        PythonSandbox.Result r = sandbox.executeScript(
                script, access, List.of(target.toAbsolutePath().toString()), null);

        assertTrue(r.ok(), "脚本执行失败: " + r.error() + " / " + r.stderr());
        assertFalse(r.stdout().contains("跳过（不是文件）"),
                "工作区文件应可读，不该被判为不存在: " + r.stdout());
        assertTrue(r.stdout().contains("参数过多"), "未检出参数过多: " + r.stdout());
        assertTrue(r.stdout().contains("裸 except"), "未检出裸 except: " + r.stdout());
    }

    @Test
    @DisplayName("追加只读工作区后，仍不能写入工作区或 skill 目录")
    void extraReadableRootGrantsNoWriteAccess(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path skillDir = Files.createDirectories(dir.resolve("skill"));
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path guarded = workspace.resolve("keep.txt");
        Files.writeString(guarded, "ORIGINAL");

        Path script = skillDir.resolve("w.py");
        Files.writeString(script, """
                import sys
                target = sys.argv[1]
                print(open(target).read())     # 读应当成功
                try:
                    with open(target, 'w') as f:
                        f.write('TAMPERED')
                    print('WROTE_WORKSPACE')
                except Exception:
                    print('WORKSPACE_READONLY')
                """);

        ScriptAccess access = ScriptAccess.readOnly(skillDir).plusReadable(workspace);
        PythonSandbox.Result r = sandbox.executeScript(
                script, access, List.of(guarded.toAbsolutePath().toString()), null);

        assertTrue(r.stdout().contains("ORIGINAL"), "只读根应可读: " + r.stdout() + r.stderr());
        assertTrue(r.stdout().contains("WORKSPACE_READONLY"),
                "追加只读根不该顺带给出写权限: " + r.stdout());
        assertEquals("ORIGINAL", Files.readString(guarded), "只读根内的文件被篡改");
    }

    @Test
    @DisplayName("读不到文件时明确报错并非零退出，不伪装成扫描通过")
    void unreadableTargetFailsLoudly(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path shipped = Path.of("src/main/resources/skills/clean-code/scripts/smell_scan.py");
        assumeTrue(Files.exists(shipped), "脚本未随包发布");
        Path script = dir.resolve("smell_scan.py");
        Files.copy(shipped, script);

        // 沙箱外的路径：脚本读不到它，必须明说而不是静默跳过
        PythonSandbox.Result r = sandbox.executeScriptReadOnly(
                script, dir, List.of("C:/definitely/not/here.py"), null);

        assertTrue(r.stdout().contains("无法读取"), "应明确报错: " + r.stdout());
        assertTrue(r.stdout().contains("结果不完整"), "应提示结果不完整: " + r.stdout());
        assertFalse(r.ok(), "一个文件都没扫到却返回成功，会掩盖问题");
    }
}
