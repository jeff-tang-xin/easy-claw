package com.xinl.easyclaw.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WorktreeService} 的真实 git 集成测试：在临时目录起真实仓库，
 * 覆盖 create / attach / remove / listBranches 与参数校验。
 * 本机无 git 时整组跳过（Assumptions），不算失败。
 */
class WorktreeServiceTest {

    @TempDir
    Path repoDir;

    private final WorktreeService service = new WorktreeService();

    @BeforeAll
    static void requireGit() {
        boolean available;
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            available = p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机 PATH 无 git，跳过 worktree 测试");
    }

    @BeforeEach
    void initRepo() throws Exception {
        git(repoDir, "init");
        // 初始 unborn 分支改名 main，兼容不支持 init -b 的旧版 git
        git(repoDir, "checkout", "-b", "main");
        git(repoDir, "config", "user.name", "tester");
        git(repoDir, "config", "user.email", "tester@example.com");
        Files.writeString(repoDir.resolve("README.md"), "# test\n");
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-m", "init");
    }

    @Test
    @DisplayName("create：新分支 worktree 就位，内容与基准一致，分支列表可见")
    void create_newBranch() {
        WorktreeService.WorktreeResult r =
                service.create(repoDir, "session-1", "easyclaw/feat-x", null);

        assertTrue(r.ok(), r.message());
        Path wt = r.path();
        assertEquals(service.worktreePathOf(repoDir, "session-1"), wt);
        assertTrue(Files.isDirectory(wt));
        assertTrue(Files.exists(wt.resolve("README.md")), "worktree 应检出基准内容");
        assertTrue(service.listBranches(repoDir).contains("easyclaw/feat-x"));
        assertEquals("easyclaw/feat-x", service.currentBranch(wt));
    }

    @Test
    @DisplayName("create：显式基准分支")
    void create_withBase() throws Exception {
        git(repoDir, "branch", "base-b");
        WorktreeService.WorktreeResult r =
                service.create(repoDir, "session-2", "easyclaw/feat-y", "base-b");
        assertTrue(r.ok(), r.message());
        assertEquals("easyclaw/feat-y", service.currentBranch(r.path()));
    }

    @Test
    @DisplayName("attach：挂载已有分支")
    void attach_existingBranch() throws Exception {
        git(repoDir, "branch", "feat-z");
        WorktreeService.WorktreeResult r =
                service.attach(repoDir, "session-3", "feat-z");
        assertTrue(r.ok(), r.message());
        assertTrue(Files.isDirectory(r.path()));
        assertEquals("feat-z", service.currentBranch(r.path()));
    }

    @Test
    @DisplayName("listOccupiedBranches：主工作区与已挂载分支计入，空闲分支不计入")
    void listOccupiedBranches() throws Exception {
        git(repoDir, "branch", "free-b");
        WorktreeService.WorktreeResult r =
                service.create(repoDir, "session-occ", "easyclaw/occ", null);
        assertTrue(r.ok(), r.message());

        Map<String, String> occupied = service.listOccupiedBranches(repoDir);
        // @TempDir 给 8.3 短名（XINL~1.TAN）而 git porcelain 输出长路径名，
        // Path.equals 按元素字符串比较会误判 —— 用 Files.isSameFile 比文件身份
        assertTrue(Files.isSameFile(repoDir, Path.of(occupied.get("main"))), "主工作区 checkout 的分支应计入");
        assertTrue(Files.isSameFile(r.path(), Path.of(occupied.get("easyclaw/occ"))), "已挂载分支应计入");
        assertFalse(occupied.containsKey("free-b"), "未被任何 worktree checkout 的分支不应计入");
    }

    @Test
    @DisplayName("create：分支名非法 → 参数异常，不执行 git")
    void create_invalidBranchName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(repoDir, "session-4", "bad name", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(repoDir, "session-4", "../escape", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(repoDir, "session-4", "-d", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(repoDir, "session-4", "a..b", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(repoDir, "session-4", "tail.lock", null));
    }

    @Test
    @DisplayName("sessionId 非法 → 参数异常")
    void invalidSessionId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.worktreePathOf(repoDir, "../.."));
        assertThrows(IllegalArgumentException.class,
                () -> service.remove(repoDir, "a/b", false));
    }

    @Test
    @DisplayName("remove：干净 worktree 正常移除")
    void remove_clean() {
        WorktreeService.WorktreeResult created =
                service.create(repoDir, "session-5", "easyclaw/rm-1", null);
        assertTrue(created.ok(), created.message());

        WorktreeService.WorktreeResult r = service.remove(repoDir, "session-5", false);
        assertTrue(r.ok(), r.message());
        assertFalse(Files.exists(created.path()), "worktree 目录应已删除");
    }

    @Test
    @DisplayName("remove：有未提交改动时保护现场，force 才删")
    void remove_dirtyNeedsForce() throws Exception {
        WorktreeService.WorktreeResult created =
                service.create(repoDir, "session-6", "easyclaw/rm-2", null);
        assertTrue(created.ok(), created.message());
        Files.writeString(created.path().resolve("README.md"), "# dirty\n");

        WorktreeService.WorktreeResult refused = service.remove(repoDir, "session-6", false);
        assertFalse(refused.ok(), "有未提交改动时不应静默删除");
        assertTrue(Files.exists(created.path()), "现场应保留");

        WorktreeService.WorktreeResult forced = service.remove(repoDir, "session-6", true);
        assertTrue(forced.ok(), forced.message());
        assertFalse(Files.exists(created.path()));
    }

    @Test
    @DisplayName("remove：目录不存在时幂等 ok")
    void remove_missingIsIdempotent() {
        WorktreeService.WorktreeResult r = service.remove(repoDir, "session-7", false);
        assertTrue(r.ok(), r.message());
    }

    @Test
    @DisplayName("create：目标目录已存在时拒绝覆盖")
    void create_targetExists() throws Exception {
        Path occupied = service.worktreePathOf(repoDir, "session-8");
        Files.createDirectories(occupied);
        WorktreeService.WorktreeResult r =
                service.create(repoDir, "session-8", "easyclaw/dup", null);
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("listBranches / currentBranch 基本读取")
    void listAndCurrent() {
        List<String> branches = service.listBranches(repoDir);
        assertTrue(branches.contains("main"), "应含 main，实际: " + branches);
        assertEquals("main", service.currentBranch(repoDir));
    }

    // ==================== 测试内 git helper（输出可控，管道直读） ====================

    private static String git(Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        assertTrue(exited, "git 超时: " + String.join(" ", args));
        assertEquals(0, p.exitValue(), "git 失败: " + String.join(" ", args) + "\n" + out);
        return out;
    }
}
