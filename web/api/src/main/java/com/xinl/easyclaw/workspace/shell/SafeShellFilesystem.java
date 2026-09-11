package com.xinl.easyclaw.workspace.shell;

import io.agentscope.core.agent.RuntimeContext;
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

    private final int defaultTimeout;
    private final int maxOutputBytes;

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
     * 与父类私有 resolveExecuteCwd 等价的 cwd 解析：shellCwd 优先（Easy-Claw 装配恒非空），
     * 其次按命名空间前缀，兜底文件系统 cwd。
     */
    private Path resolveExecuteCwdSafe(RuntimeContext rc) {
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
