package com.xinl.easyclaw.workspace.shell;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复 vendored {@link LocalFilesystemWithShell#execute} 三重进程缺陷的安全 shell 实现。
 * （2026-09-10 jstack 结案：boundedElastic 线程死于 ProcessImpl.waitFor——shell 卡死 →
 * ReAct acting 步挂起 → 回合永不结束 → callGates 闸门占死 → 新消息不调 LLM）
 *
 * <ol>
 *   <li>vendored 先 waitFor 再读 stdout/stderr：输出写满 Windows 管道缓冲（~8KB）即父子互等；
 *       本实现把 stdout/stderr 重定向到临时文件，子进程直写文件不经过管道，死锁从根上消除。
 *   <li>vendored 超时分支先 readAllBytes()（进程活着流不 EOF 则永久阻塞）才 destroyForcibly()
 *       ——destroy 永远执行不到；本实现超时先杀整棵进程树（含 cmd /c 的孙进程）再读已产出输出。
 *   <li>vendored 从不关闭 stdin：等输入的命令（git commit 无 -m 等）干等超时再撞上缺陷 2；
 *       本实现 start() 后立即关闭 stdin，子进程收到 EOF 自行退出。
 * </ol>
 *
 * <p>除 execute() 外全部行为继承 vendored 父类，输出格式（[stderr] 前缀、Exit code 尾行、
 * 截断提示）与父类保持一致，避免 Agent 侧解析漂移。
 */
public class SafeShellFilesystem extends LocalFilesystemWithShell {

    private static final Logger log = LoggerFactory.getLogger(SafeShellFilesystem.class);

    /** 超时强杀后等待进程死透的宽限（秒）。 */
    private static final int KILL_GRACE_SECONDS = 5;

    /**
     * RuntimeContext 属性键：会话 worktree 根（String 路径）。由 AgentService.buildContext
     * 在「<workspace>/.easyclaw-worktrees/<sessionId> 目录存在」时塞入；stringAttributes
     * 会被 RuntimeContext.Builder.from 复制，故派遣的子 Agent 自动继承同一路由。
     */
    public static final String WORKTREE_CTX_KEY = "easyclaw.worktree.root";

    /** delegate 的搜索大小上限（MB）：与 SafeShellFilesystemSpec 装配 lower/projectFs 的取值一致。 */
    private static final int DELEGATE_MAX_FILE_SIZE_MB = 10;

    private final int defaultTimeout;
    private final int maxOutputBytes;
    // 父类对应字段为 private 不可见，自存一份供 worktree delegate 构造使用
    private final LocalFsMode fsMode;
    private final PathPolicy fsPathPolicy;

    /** worktree 根 → 同配置（mode/pathPolicy/namespaceFactory）的解析 delegate，懒建缓存。 */
    private final ConcurrentHashMap<Path, WorktreeFilesystem> worktreeDelegates = new ConcurrentHashMap<>();

    public SafeShellFilesystem(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        super(rootDir, mode, pathPolicy, timeout, maxOutputBytes, env, inheritEnv,
                namespaceFactory, shellCwd);
        // 父类对应字段为 private 不可见，自存一份供 execute() 使用
        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.fsMode = mode;
        this.fsPathPolicy = pathPolicy;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("Error: Command must be a non-empty string.", 1, false);
        }
        int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeout;
        if (effectiveTimeout <= 0) {
            throw new IllegalArgumentException(
                    "timeout must be positive, got " + effectiveTimeout);
        }

        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            Path workDir = resolveExecuteCwdSafe(runtimeContext);
            String osName = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb =
                    (osName.contains("win")
                                    ? new ProcessBuilder("cmd.exe", "/c", command)
                                    : new ProcessBuilder("sh", "-c", command))
                            .directory(workDir.toFile());

            // 缺陷 1 修复：stdout/stderr 直写临时文件、不经管道 → 无缓冲互等死锁
            stdoutFile = Files.createTempFile("easyclaw-shell-out-", ".log");
            stderrFile = Files.createTempFile("easyclaw-shell-err-", ".log");
            pb.redirectOutput(stdoutFile.toFile());
            pb.redirectError(stderrFile.toFile());

            Process proc = pb.start();
            // 缺陷 3 修复：立即关闭 stdin，等输入的命令收到 EOF 自行退出
            proc.getOutputStream().close();

            boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                // 缺陷 2 修复：先杀进程树再读输出，顺序不可颠倒
                killProcessTree(proc);
                log.warn("shell 命令超时被强杀: timeout={}s, command={}",
                        effectiveTimeout, abbreviate(command));
                StringBuilder msg = new StringBuilder()
                        .append("Error: Command timed out after ").append(effectiveTimeout)
                        .append(" seconds. The process tree has been forcibly killed.");
                if (timeoutSeconds == null) {
                    msg.append(" For long-running commands, re-run using the timeout parameter.");
                }
                String partialOut = readQuietly(stdoutFile);
                if (!partialOut.isBlank()) {
                    msg.append("\n\nPartial output before kill:\n").append(truncate(partialOut));
                }
                String partialErr = readQuietly(stderrFile);
                if (!partialErr.isBlank()) {
                    msg.append("\n[stderr] ")
                            .append(truncate(partialErr.strip()).replace("\n", "\n[stderr] "));
                }
                return new ExecuteResponse(msg.toString(), 124, false);
            }

            // 正常完成：从临时文件读回输出，拼装格式与 vendored 父类一致
            String stdout = readQuietly(stdoutFile);
            String stderr = readQuietly(stderrFile);
            StringBuilder output = new StringBuilder();
            if (!stdout.isEmpty()) {
                output.append(stdout);
            }
            if (!stderr.isBlank()) {
                for (String line : stderr.strip().split("\n")) {
                    if (!output.isEmpty()) {
                        output.append('\n');
                    }
                    output.append("[stderr] ").append(line);
                }
            }
            String outputStr = output.isEmpty() ? "<no output>" : output.toString();

            boolean truncated = false;
            if (outputStr.length() > maxOutputBytes) {
                outputStr = outputStr.substring(0, maxOutputBytes)
                        + "\n\n... Output truncated at " + maxOutputBytes + " bytes.";
                truncated = true;
            }
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                outputStr = outputStr.stripTrailing() + "\n\nExit code: " + exitCode;
            }
            return new ExecuteResponse(outputStr, exitCode, truncated);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("shell 命令执行失败: {}", e.getMessage(), e);
            return new ExecuteResponse(
                    "Error executing command (" + e.getClass().getSimpleName() + "): "
                            + e.getMessage(),
                    1, false);
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    /**
     * 会话 worktree 路由：当 RuntimeContext 携带 {@link #WORKTREE_CTX_KEY}（目录须仍存在），
     * 路径解析改由锚定 worktree 根的同配置 delegate 完成——overlay 层的写/改/删/读（upper 侧）
     * 全部落进 worktree；overlay 的 copy-on-write 语义天然保证主工作区不被写入。
     * 未携带该键（或目录已被手动删除）时回退父类主根解析，行为与改造前完全一致。
     */
    @Override
    protected Path resolvePath(RuntimeContext rc, String key) {
        Path worktreeRoot = resolveWorktreeRoot(rc);
        if (worktreeRoot == null) {
            return super.resolvePath(rc, key);
        }
        return worktreeDelegate(worktreeRoot).resolve(rc, key);
    }

    /**
     * 取本回合的 worktree 根：上下文未绑定、绑定目录已被删除（手动清理等）均返回 null
     * ——后者记 WARN 并回退主工作区根，保证回合可用而不是整轮报错。
     */
    private Path resolveWorktreeRoot(RuntimeContext rc) {
        if (rc == null) {
            return null;
        }
        String raw = rc.get(WORKTREE_CTX_KEY, String.class);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path root = Path.of(raw);
        if (!Files.isDirectory(root)) {
            log.warn("会话 worktree 目录不存在，回退主工作区根: {}", root);
            return null;
        }
        return root;
    }

    /** 每个 worktree 根一个解析 delegate：cwd=worktree 根，mode/pathPolicy/命名空间与本实例一致。 */
    private WorktreeFilesystem worktreeDelegate(Path worktreeRoot) {
        return worktreeDelegates.computeIfAbsent(worktreeRoot, root -> new WorktreeFilesystem(
                root, fsMode, fsPathPolicy, DELEGATE_MAX_FILE_SIZE_MB, getNamespaceFactory()));
    }

    /**
     * 锚定 worktree 根的纯解析实例：不 override 任何方法，行为即 vendored 原版
     * （天然不存在「resolvePath 再路由回 worktree」的递归）。
     * vendored resolvePath 是 protected 且与本类不同包，不能经 LocalFilesystem 引用访问，
     * 故以私有嵌套子类 + 包内包装方法开放调用（nestmate 访问，对外不可见）。
     */
    private static final class WorktreeFilesystem extends LocalFilesystem {
        WorktreeFilesystem(
                Path rootDir,
                LocalFsMode mode,
                PathPolicy pathPolicy,
                int maxFileSizeMb,
                NamespaceFactory namespaceFactory) {
            super(rootDir, mode, pathPolicy, maxFileSizeMb, namespaceFactory);
        }

        /** 开放继承的 protected resolvePath 给外部类（本嵌套类的 nestmate）调用。 */
        Path resolve(RuntimeContext rc, String key) {
            return resolvePath(rc, key);
        }
    }

    /**
     * 与父类私有 resolveExecuteCwd 等价的 cwd 解析：会话绑定了 worktree 时优先以 worktree
     * 根为 cwd（shell 命令直接作用于隔离检出，git 命令自然操作会话分支）；
     * 其次 shellCwd（Easy-Claw 装配恒非空），再次按命名空间前缀，兜底文件系统 cwd。
     */
    private Path resolveExecuteCwdSafe(RuntimeContext rc) {
        Path worktreeRoot = resolveWorktreeRoot(rc);
        if (worktreeRoot != null) {
            return worktreeRoot;
        }
        if (getShellCwd() != null) {
            return getShellCwd();
        }
        NamespaceFactory nsf = getNamespaceFactory();
        if (nsf == null) {
            return getCwd();
        }
        List<String> ns = nsf.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            return getCwd();
        }
        Path namespaced = getCwd();
        for (String segment : ns) {
            namespaced = namespaced.resolve(segment);
        }
        try {
            Files.createDirectories(namespaced);
        } catch (IOException e) {
            log.warn("创建命名空间目录失败 {}: {}", namespaced, e.getMessage());
        }
        return namespaced;
    }

    /** 强杀进程及其整棵后代树（cmd /c 启动的孙进程一并清理），并等待强杀生效。 */
    private void killProcessTree(Process proc) {
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
            return Files.readString(file, shellOutputCharset());
        } catch (IOException e) {
            return "";
        }
    }

    /** 与 vendored 包私有 outputCharset 等价的判定：非 Windows 走 UTF-8，Windows 走 native.encoding。 */
    static Charset shellOutputCharset() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return StandardCharsets.UTF_8;
        }
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && Charset.isSupported(nativeEncoding)) {
            return Charset.forName(nativeEncoding);
        }
        return Charset.defaultCharset();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响执行结果
        }
    }

    private String truncate(String s) {
        return s.length() <= maxOutputBytes
                ? s
                : s.substring(0, maxOutputBytes)
                        + "\n\n... Output truncated at " + maxOutputBytes + " bytes.";
    }

    private static String abbreviate(String command) {
        return command.length() <= 120 ? command : command.substring(0, 120) + "...";
    }
}
