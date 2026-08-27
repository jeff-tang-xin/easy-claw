package com.xinl.easyclaw.python;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 沙箱边界实测。
 *
 * <p>这些用例的作用是<b>证伪</b>：每一条都在尝试逃逸，断言逃逸失败。
 * 安全属性不能靠读文档确认，必须实际打一遍。
 */
class PythonSandboxTest {

    private static PythonSandbox sandbox;
    private static boolean ready;

    @BeforeAll
    static void setUp() {
        sandbox = new PythonSandbox();
        ready = sandbox.isAvailable();
    }

    @Test
    @DisplayName("基本执行：stdout 应被捕获")
    void capturesStdout() {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute("print(sum(range(101)))", null, null);
        assertAll(
                () -> assertTrue(r.ok(), "应成功: " + r.error()),
                () -> assertTrue(r.stdout().contains("5050"), r.stdout()));
    }

    @Test
    @DisplayName("语法错误应返回 Python 侧 traceback 而非 Java 异常")
    void syntaxErrorIsReported() {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute("def broken(:", null, null);
        assertAll(
                () -> assertFalse(r.ok()),
                () -> assertTrue(r.error() != null && !r.error().isBlank(), "应有错误信息"));
    }

    @Test
    @DisplayName("死循环必须被超时中断，不能挂死")
    void infiniteLoopIsInterrupted() {
        assumeTrue(ready, "需要 GraalVM");
        long start = System.currentTimeMillis();
        PythonSandbox.Result r = sandbox.execute("while True: pass", null, Duration.ofSeconds(2));
        long elapsed = System.currentTimeMillis() - start;
        assertAll(
                () -> assertFalse(r.ok(), "死循环不应报成功"),
                () -> assertTrue(r.error().contains("超时"), "应报超时: " + r.error()),
                () -> assertTrue(elapsed < 20000, "应在超时后尽快返回，实际 " + elapsed + "ms"));
    }

    @Test
    @DisplayName("allowedDir=null 时文件读写必须被拒绝")
    void fileAccessDeniedByDefault() {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute(
                "open('probe.txt','w').write('x')\nprint('WROTE')", null, null);
        assertAll(
                () -> assertFalse(r.stdout().contains("WROTE"), "写入不应成功: " + r.stdout()),
                () -> assertFalse(r.ok(), "应失败"));
    }

    @Test
    @DisplayName("子进程创建必须被拒绝")
    void subprocessDenied() {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute(
                "import subprocess\nsubprocess.run(['cmd','/c','echo','ESCAPED'])\nprint('RAN')",
                null, null);
        assertFalse(r.stdout().contains("ESCAPED"), "不应能起子进程: " + r.stdout());
    }

    @Test
    @DisplayName("Java 互操作必须被拒绝（否则可绕过所有限制）")
    void hostAccessDenied() {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute(
                "import java\nprint(java.lang.System.getProperty('user.home'))", null, null);
        assertFalse(r.ok() && r.stdout().contains(":"), "不应能访问 Java 类: " + r.stdout());
    }

    @Test
    @DisplayName("放开目录后：目录内可读写")
    void allowedDirIsWritable(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        PythonSandbox.Result r = sandbox.execute(
                "open('out.txt','w').write('hello')\nprint('OK')", dir, null);
        assertAll(
                () -> assertTrue(r.ok(), "应成功: " + r.error() + r.stderr()),
                () -> assertTrue(Files.exists(dir.resolve("out.txt")), "文件应落在目录内"));
    }

    /**
     * 关键用例：确认放开一个目录后能否用 .. 逃逸到父目录。
     *
     * <p>这条用例的结果决定 allowedDir 的实现是否够用——若能逃逸，则
     * {@code FileSystem.newFileSystem(default)} 不构成隔离，必须换实现。
     */
    @Test
    @DisplayName("放开目录后：不得用 .. 逃逸到父目录")
    void cannotEscapeAllowedDir(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path secret = dir.getParent().resolve("secret-" + System.nanoTime() + ".txt");
        Files.writeString(secret, "TOP_SECRET_CONTENT");
        try {
            String code = "print(open(r'" + secret.toString().replace("\\", "\\\\") + "').read())";
            PythonSandbox.Result r = sandbox.execute(code, dir, null);
            assertFalse(
                    r.stdout().contains("TOP_SECRET_CONTENT"),
                    "沙箱外文件不应可读！实际读到了: " + r.stdout());
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    @DisplayName("放开目录后：不得通过符号链接逃逸")
    void cannotEscapeViaSymlink(@TempDir Path dir) throws Exception {
        assumeTrue(ready, "需要 GraalVM");
        Path secret = dir.getParent().resolve("slsecret-" + System.nanoTime() + ".txt");
        Files.writeString(secret, "SYMLINK_LEAK");
        Path link = dir.resolve("innocent.txt");
        boolean linked;
        try {
            Files.createSymbolicLink(link, secret);
            linked = true;
        } catch (Exception noPrivilege) {
            linked = false;
        }
        // Windows 非管理员无法建符号链接，与 core 模块同类用例情况一致
        assumeTrue(linked, "当前环境不允许创建符号链接");
        try {
            PythonSandbox.Result r = sandbox.execute("print(open('innocent.txt').read())", dir, null);
            assertFalse(r.stdout().contains("SYMLINK_LEAK"), "符号链接不应能穿透沙箱: " + r.stdout());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(secret);
        }
    }
}
