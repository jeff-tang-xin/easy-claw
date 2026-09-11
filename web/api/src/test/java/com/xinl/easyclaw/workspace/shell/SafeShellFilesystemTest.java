package com.xinl.easyclaw.workspace.shell;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeShellFilesystem#execute} 定向测试——逐项对照 vendored 三重缺陷的修复：
 * stdin 未关（缺陷 3）、管道缓冲死锁（缺陷 1）、超时分支顺序颠倒（缺陷 2），
 * 以及输出格式与 vendored 父类的兼容性。
 *
 * <p>命令均为 Windows cmd 语法（本项目运行环境）。
 */
class SafeShellFilesystemTest {

    @TempDir
    Path tempDir;

    private SafeShellFilesystem newFs(int timeoutSeconds) {
        return new SafeShellFilesystem(
                tempDir, null, null, timeoutSeconds, 100_000, null, false, null, tempDir);
    }

    @Test
    void 正常命令输出与退出码() {
        ExecuteResponse resp = newFs(30).execute(RuntimeContext.empty(), "echo hello-shell", null);
        assertEquals(0, resp.exitCode());
        assertTrue(resp.output().contains("hello-shell"));
    }

    @Test
    void 等stdin的命令立即收到EOF不再干等超时() {
        // findstr 无文件参数时从 stdin 读取；vendored 不关 stdin 会干等满整个 timeout
        long start = System.currentTimeMillis();
        ExecuteResponse resp = newFs(60).execute(RuntimeContext.empty(), "findstr \"^\"", null);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 10_000, "stdin EOF 应使命令立即退出，实际耗时 " + elapsed + "ms");
        assertNotEquals(124, resp.exitCode(), "不应走到超时强杀分支");
    }

    @Test
    void 超大输出不触发管道缓冲死锁() {
        // 2000 行 ≈ 50KB，远超 Windows 管道缓冲 ~8KB；vendored 先 waitFor 后读输出必死锁到超时
        ExecuteResponse resp = newFs(60).execute(RuntimeContext.empty(),
                "for /l %i in (1,1,2000) do @echo line-%i-padding-padding", null);
        assertEquals(0, resp.exitCode());
        assertTrue(resp.output().contains("line-2000"), "输出应完整读回，实际尾部缺失");
    }

    @Test
    void 超时强杀返回124且带部分输出() {
        long start = System.currentTimeMillis();
        ExecuteResponse resp = newFs(2).execute(RuntimeContext.empty(),
                "echo before-sleep & ping -n 30 127.0.0.1 >nul", null);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(124, resp.exitCode());
        assertTrue(resp.output().contains("timed out after 2 seconds"));
        assertTrue(resp.output().contains("before-sleep"), "强杀后应能读到已产出的部分输出");
        // vendored 在此场景死于 readAllBytes 永不返回；修复后 = 超时 2s + 强杀宽限
        assertTrue(elapsed < 15_000, "超时后应迅速返回（含强杀宽限），实际 " + elapsed + "ms");
    }

    @Test
    void 非零退出码附加ExitCode尾行() {
        ExecuteResponse resp = newFs(30).execute(RuntimeContext.empty(), "exit 3", null);
        assertEquals(3, resp.exitCode());
        assertTrue(resp.output().contains("Exit code: 3"));
    }

    @Test
    void stderr逐行带前缀与stdout合并() {
        ExecuteResponse resp = newFs(30).execute(RuntimeContext.empty(),
                "echo out-line & echo err-line 1>&2", null);
        assertEquals(0, resp.exitCode());
        assertTrue(resp.output().contains("out-line"));
        assertTrue(resp.output().contains("[stderr] err-line"));
    }
}
