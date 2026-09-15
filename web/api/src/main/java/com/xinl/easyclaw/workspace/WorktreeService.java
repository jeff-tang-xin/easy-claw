package com.xinl.easyclaw.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * git worktree 操作层：会话级工作区隔离（2026-09-14 会话 ↔ worktree 挂钩，MVP）。
 *
 * <p>每个挂了分支的会话在 {@code <workspace>/.easyclaw-worktrees/<sessionId>/} 拥有
 * 独立工作树（checkout 出独立分支），会话之间改代码互不踩踏；未指定分支的会话
 * 不经过本类，行为与以往完全一致。
 *
 * <h2>进程执行纪律</h2>
 * 与 {@code SafeShellFilesystem} 同源的三条教训，缺一不可：
 * <ol>
 *   <li>stdout/stderr 重定向临时文件，子进程直写文件不经过管道（防管道缓冲写满互等）；</li>
 *   <li>{@code start()} 后立即关闭 stdin（防 {@code git worktree remove} 等交互式命令干等）；</li>
 *   <li>超时先杀整棵进程树再读已产出输出。</li>
 * </ol>
 *
 * <h2>注入面</h2>
 * 所有参数以 {@link ProcessBuilder} 独立 argv 传递，全程不经 shell 拼接；
 * 分支名 / sessionId 另过白名单校验（git ref 规则的严格子集），构成第二道防线。
 */
@Service
public class WorktreeService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);

    /** worktree 总根目录名（位于 workspace 根下，必须加入 .gitignore） */
    public static final String WORKTREE_ROOT_NAME = ".easyclaw-worktrees";

    /** git 命令超时（秒）：worktree add/remove 均为本地操作，30s 足够 */
    private static final int GIT_TIMEOUT_SECONDS = 30;

    /** 超时强杀后等待进程死透的宽限（秒），与 SafeShellFilesystem 对齐 */
    private static final int KILL_GRACE_SECONDS = 5;

    /**
     * 分支名白名单：git check-ref-format 的严格子集 —— 仅 ASCII 字母数字与
     * {@code . _ / -}，首字符必须字母数字；另禁 {@code ..}、{@code /} 结尾、
     * {@code .lock} 结尾。宁严勿宽， exotic 分支名走手动建 worktree。
     */
    private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$");

    /** sessionId 白名单：与 WorkspaceAccessGuard 的 ID 字符集一致 */
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** worktree 操作结果。message 面向用户可读；path 仅成功时有值。 */
    public record WorktreeResult(boolean ok, String message, Path path) {
        public static WorktreeResult ok(Path path, String message) {
            return new WorktreeResult(true, message, path);
        }

        public static WorktreeResult fail(String message) {
            return new WorktreeResult(false, message, null);
        }
    }

    private record GitResult(int exitCode, String output) {
    }

    /** worktree 总根：<workspace>/.easyclaw-worktrees */
    public static Path worktreeRoot(Path workspacePath) {
        return workspacePath.resolve(WORKTREE_ROOT_NAME);
    }

    /** 会话 worktree 路径：<workspace>/.easyclaw-worktrees/<sessionId> */
    public static Path worktreePathOf(Path workspacePath, String sessionId) {
        validateSessionId(sessionId);
        return worktreeRoot(workspacePath).resolve(sessionId);
    }

    /**
     * 新建分支并检出到会话 worktree：{@code git worktree add <path> -b <branch> [base]}。
     *
     * @param baseRef 基准引用，null/空白 = 当前 HEAD；MVP 限定为分支名级别的引用
     *                （同 {@link #BRANCH_NAME} 白名单，另放行字面量 HEAD）
     */
    public WorktreeResult create(Path workspacePath, String sessionId,
                                 String branchName, String baseRef) {
        validateBranchName(branchName);
        Path target = worktreePathOf(workspacePath, sessionId);
        if (Files.exists(target)) {
            return WorktreeResult.fail("worktree 目录已存在: " + target);
        }
        List<String> args = new ArrayList<>(
                List.of("worktree", "add", target.toString(), "-b", branchName));
        if (baseRef != null && !baseRef.isBlank()) {
            if (!"HEAD".equals(baseRef)) {
                validateBranchName(baseRef);
            }
            args.add(baseRef);
        }
        GitResult r = runGit(workspacePath, args);
        if (r.exitCode() == 0) {
            log.info("已创建会话 worktree: session={}, branch={}, path={}", sessionId, branchName, target);
            return WorktreeResult.ok(target, "已创建 worktree（新分支 " + branchName + "）");
        }
        return WorktreeResult.fail("git worktree add 失败: " + r.output());
    }

    /**
     * 把已有分支检出到会话 worktree：{@code git worktree add <path> <branch>}。
     * 注意 git 不允许同一分支被两处同时 checkout（主工作区正占用的分支会失败），
     * 失败信息原样带回给调用方。
     */
    public WorktreeResult attach(Path workspacePath, String sessionId, String branchName) {
        validateBranchName(branchName);
        Path target = worktreePathOf(workspacePath, sessionId);
        if (Files.exists(target)) {
            return WorktreeResult.fail("worktree 目录已存在: " + target);
        }
        GitResult r = runGit(workspacePath,
                List.of("worktree", "add", target.toString(), branchName));
        if (r.exitCode() == 0) {
            log.info("已挂载会话 worktree: session={}, branch={}, path={}", sessionId, branchName, target);
            return WorktreeResult.ok(target, "已挂载 worktree（分支 " + branchName + "）");
        }
        return WorktreeResult.fail("git worktree add 失败: " + r.output());
    }

    /**
     * 移除会话 worktree。目录不存在时幂等 ok（可能已被用户手动清理）。
     *
     * @param force false 时有未提交改动会被 git 拒绝（保护现场），错误信息带回；
     *              true 加 {@code --force} 强制删除（调用方须先取得用户确认）
     */
    public WorktreeResult remove(Path workspacePath, String sessionId, boolean force) {
        Path target = worktreePathOf(workspacePath, sessionId);
        if (!Files.exists(target)) {
            return WorktreeResult.ok(target, "worktree 目录不存在，跳过清理");
        }
        List<String> args = new ArrayList<>(List.of("worktree", "remove", target.toString()));
        if (force) {
            args.add("--force");
        }
        GitResult r = runGit(workspacePath, args);
        if (r.exitCode() == 0) {
            log.info("已移除会话 worktree: session={}, path={}", sessionId, target);
            return WorktreeResult.ok(target, "已移除 worktree");
        }
        // Windows 常见失败：IDE/进程占用文件、存在未提交改动 —— 原样透传 git 的说明
        return WorktreeResult.fail("git worktree remove 失败: " + r.output());
    }

    /** 列出本地分支（短名）。仓库不可用或命令失败时返回空列表，由调用方决定降级。 */
    public List<String> listBranches(Path workspacePath) {
        GitResult r = runGit(workspacePath, List.of("branch", "--format=%(refname:short)"));
        if (r.exitCode() != 0) {
            log.warn("列出分支失败: {}", r.output());
            return List.of();
        }
        List<String> branches = new ArrayList<>();
        for (String line : r.output().split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                branches.add(name);
            }
        }
        return branches;
    }

    /** 当前分支名（新分支的默认基准）；失败返回 null。 */
    public String currentBranch(Path workspacePath) {
        GitResult r = runGit(workspacePath, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        if (r.exitCode() != 0) {
            log.warn("读取当前分支失败: {}", r.output());
            return null;
        }
        String name = r.output().trim();
        return name.isEmpty() ? null : name;
    }

    // ==================== 分支名 / 会话 ID 校验 ====================

    private static void validateBranchName(String name) {
        if (name == null || !BRANCH_NAME.matcher(name).matches()
                || name.contains("..") || name.endsWith("/") || name.endsWith(".lock")) {
            throw new IllegalArgumentException("非法分支名: " + name);
        }
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("非法 sessionId: " + sessionId);
        }
    }

    // ==================== 进程执行（三重教训版） ====================

    private GitResult runGit(Path cwd, List<String> args) {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add("git");
        cmd.addAll(args);
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            stdoutFile = Files.createTempFile("easyclaw-git-out-", ".tmp");
            stderrFile = Files.createTempFile("easyclaw-git-err-", ".tmp");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());
            Process proc = pb.start();
            // 立即关闭 stdin：子进程收到 EOF，交互式命令不会干等输入
            proc.getOutputStream().close();
            boolean exited = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                killProcessTree(proc);
                return new GitResult(-1,
                        "git 命令超时（" + GIT_TIMEOUT_SECONDS + "s）已强杀: " + String.join(" ", args));
            }
            String out = readQuietly(stdoutFile);
            String err = readQuietly(stderrFile);
            String combined = (out + (err.isBlank() ? "" : "\n" + err)).trim();
            return new GitResult(proc.exitValue(), combined);
        } catch (IOException e) {
            return new GitResult(-1, "git 启动失败（PATH 中找不到 git？）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "等待 git 退出被中断");
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    /** 强杀进程及其整棵后代树，并等待强杀生效。 */
    private static void killProcessTree(Process proc) {
        List<ProcessHandle> descendants = proc.descendants().toList();
        proc.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            proc.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readQuietly(Path file) {
        if (file == null) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件清理失败无碍主流程
        }
    }
}
