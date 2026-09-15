package com.xinl.easyclaw.workspace.shell;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeShellFilesystem} 会话 worktree 路由定向测试——RuntimeContext 携带
 * {@link SafeShellFilesystem#WORKTREE_CTX_KEY} 时，相对路径读写与 shell cwd 全部锚定
 * worktree 根；未携带或目录已被删除时回退主工作区根（行为与改造前一致）。
 *
 * <p>命令均为 Windows cmd 语法（本项目运行环境）。
 */
class SafeShellFilesystemWorktreeTest {

    @TempDir
    Path mainRoot;

    @TempDir
    Path worktreeRoot;

    private SafeShellFilesystem fs;

    @BeforeEach
    void setUp() {
        fs = new SafeShellFilesystem(mainRoot, LocalFsMode.ROOTED,
                PathPolicy.of(List.of(mainRoot)), 30, 100_000, null, false, null, mainRoot);
    }

    private RuntimeContext boundRc() {
        return RuntimeContext.builder().sessionId("sess-1")
                .put(SafeShellFilesystem.WORKTREE_CTX_KEY, worktreeRoot.toString())
                .build();
    }

    @Test
    void 未绑定worktree时写读落主根() {
        WriteResult wr = fs.write(RuntimeContext.empty(), "plain.txt", "main-content");
        assertTrue(wr.isSuccess(), "写入应成功: " + wr.error());
        assertTrue(Files.exists(mainRoot.resolve("plain.txt")), "文件应落在主根");

        ReadResult rr = fs.read(RuntimeContext.empty(), "plain.txt", 0, 0);
        assertTrue(rr.isSuccess());
        assertEquals("main-content", rr.fileData().content());
    }

    @Test
    void 绑定worktree后写读全部落worktree且不碰主根() {
        RuntimeContext rc = boundRc();

        WriteResult wr = fs.write(rc, "wt.txt", "worktree-content");
        assertTrue(wr.isSuccess(), "写入应成功: " + wr.error());
        assertTrue(Files.exists(worktreeRoot.resolve("wt.txt")), "文件应落在 worktree");
        assertFalse(Files.exists(mainRoot.resolve("wt.txt")), "主根不应出现该文件");

        ReadResult rr = fs.read(rc, "wt.txt", 0, 0);
        assertTrue(rr.isSuccess());
        assertEquals("worktree-content", rr.fileData().content());

        assertTrue(fs.exists(rc, "wt.txt"), "exists 应命中 worktree 内文件");
    }

    @Test
    void 绑定的worktree目录被删除后回退主根() throws IOException {
        RuntimeContext rc = boundRc();
        assertTrue(fs.write(rc, "gone.txt", "x").isSuccess());
        assertTrue(Files.exists(worktreeRoot.resolve("gone.txt")));

        // 模拟用户手动清理 worktree 目录：路由应回退主根而不是整轮报错
        Files.delete(worktreeRoot.resolve("gone.txt"));
        Files.delete(worktreeRoot);

        WriteResult wr = fs.write(rc, "fallback.txt", "main-again");
        assertTrue(wr.isSuccess(), "回退后写入应成功: " + wr.error());
        assertTrue(Files.exists(mainRoot.resolve("fallback.txt")), "回退后文件应落在主根");
    }

    @Test
    void execute的cwd随worktree路由() {
        // cmd 内 echo %CD% 回显当前目录；@TempDir 目录名唯一，用它规避盘符大小写差异
        ExecuteResponse bound = fs.execute(boundRc(), "echo %CD%", null);
        assertEquals(0, bound.exitCode());
        assertTrue(bound.output().contains(worktreeRoot.getFileName().toString()),
                "绑定后 cwd 应为 worktree 根，实际输出: " + bound.output());

        ExecuteResponse unbound = fs.execute(RuntimeContext.empty(), "echo %CD%", null);
        assertEquals(0, unbound.exitCode());
        assertTrue(unbound.output().contains(mainRoot.getFileName().toString()),
                "未绑定时 cwd 应为主根，实际输出: " + unbound.output());
    }

    @Test
    void worktree内绝对路径放行() {
        RuntimeContext rc = boundRc();
        Path inside = worktreeRoot.resolve("abs.txt");

        WriteResult wr = fs.write(rc, inside.toString(), "abs-content");
        assertTrue(wr.isSuccess(), "worktree 内绝对路径应放行: " + wr.error());
        assertTrue(Files.exists(inside));
    }
}
