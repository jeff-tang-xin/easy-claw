package com.xinl.easyclaw.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 带 Shell 执行能力的 CRLF 归一化文件系统。
 * <p>
 * 继承 {@link LocalFilesystemWithShell}，提供三个修复：
 * <ol>
 *   <li>重写 read()：CRLF → LF 归一化（修复框架遗漏）</li>
 *   <li>重写 execute()：Windows 下注入 {@code chcp 65001} 切控制台到 UTF-8，
 *       并对输出做 GBK→UTF-8 容错降级（防止子进程自行切换代码页）</li>
 *   <li>重写 execute()：注入 JAVA_TOOL_OPTIONS / PYTHONIOENCODING 等环境变量提示，
 *       让子进程（Java/Python/Node）用 UTF-8 输出</li>
 * </ol>
 */
public class CrlfNormalizingLocalFilesystemWithShell extends LocalFilesystemWithShell {

    private static final Logger log = LoggerFactory.getLogger(CrlfNormalizingLocalFilesystemWithShell.class);

    /** Windows 中文系统默认代码页（GBK） */
    private static final Charset WINDOWS_GBK = Charset.forName("GBK");

    private final int defaultTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> env;
    private final boolean inheritEnv;

    public CrlfNormalizingLocalFilesystemWithShell(Path rootDir) {
        super(rootDir);
        this.defaultTimeout = 120;
        this.maxOutputBytes = 100_000;
        this.env = Map.of();
        this.inheritEnv = true;
    }

    public CrlfNormalizingLocalFilesystemWithShell(Path rootDir, NamespaceFactory namespaceFactory) {
        super(rootDir, namespaceFactory);
        this.defaultTimeout = 120;
        this.maxOutputBytes = 100_000;
        this.env = Map.of();
        this.inheritEnv = true;
    }

    public CrlfNormalizingLocalFilesystemWithShell(Path rootDir, LocalFsMode mode, PathPolicy pathPolicy,
                                                   int timeout, int maxOutputBytes,
                                                   Map<String, String> env, boolean inheritEnv,
                                                   NamespaceFactory namespaceFactory,
                                                   Path shellCwd) {
        super(rootDir, mode, pathPolicy, timeout, maxOutputBytes, env, inheritEnv, namespaceFactory, shellCwd);
        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.env = env != null ? env : Map.of();
        this.inheritEnv = inheritEnv;
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Path resolved = resolvePath(runtimeContext, filePath);
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            String normalized = content.replace("\r\n", "\n").replace("\r", "\n");

            if (normalized.isEmpty() || normalized.isBlank()) {
                return ReadResult.success(
                        new FileData("System reminder: File exists but has empty contents", "utf-8"));
            }

            String[] lines = normalized.split("\n", -1);
            int startIdx = Math.max(0, offset);
            int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;
            if (startIdx >= lines.length) {
                return ReadResult.fail(
                        "Line offset " + offset + " exceeds file length (" + lines.length + " lines)");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                if (i > startIdx) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            return ReadResult.success(new FileData(sb.toString(), "utf-8"));
        } catch (Exception e) {
            return ReadResult.fail("Error reading file '" + filePath + "': " + e.getMessage());
        }
    }

    @Override
    public ExecuteResponse execute(RuntimeContext ctx, String command, Integer timeoutOverride) {
        if (command == null || command.isBlank()) {
            log.warn("execute: 空命令");
            return new ExecuteResponse("Error: Command must be a non-empty string.", 1, false);
        }
        int timeout = timeoutOverride != null ? timeoutOverride : defaultTimeout;
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive, got: " + timeout);
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        Path cwd = getShellCwd() != null ? getShellCwd() : getCwd();
        log.info("execute 调用: command=[{}], timeout={}, isWindows={}, cwd={}, shellCwd={}, getCwd={}",
                command, timeout, isWindows, cwd, getShellCwd(), getCwd());

        // Windows: 注入 chcp 65001 切控制台代码页到 UTF-8，并注入编码环境变量
        // Linux/macOS: 直接执行
        String actualCommand = isWindows
                ? "chcp 65001 >nul 2>&1 && " + command
                : command;

        try {
            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", actualCommand);
            } else {
                pb = new ProcessBuilder("sh", "-c", actualCommand);
            }

            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(true);

            // 环境变量：继承 Java 进程环境（PATH/COMSPEC 等必须保留）+ 显式配置的 env + 编码提示
            // 与父类语义一致：ProcessBuilder.environment() 默认继承当前进程完整环境，
            // 只在有显式 env 配置时才追加覆盖；编码提示用 putIfAbsent 兜底
            Map<String, String> processEnv = pb.environment();
            if (env != null && !env.isEmpty()) {
                processEnv.putAll(env);
            }
            injectEncodingHints(processEnv, isWindows);

            long startMs = System.currentTimeMillis();
            Process process = pb.start();
            log.info("execute: 进程已启动, pid={}, cwd={}", process.pid(), cwd);
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startMs;

            byte[] rawBytes = process.getInputStream().readAllBytes();

            if (!finished) {
                process.destroyForcibly();
                String truncated = decodeOutput(rawBytes, isWindows)
                        + "\n\n[Command timed out after " + timeout + "s]"
                        + " (elapsed " + elapsed + "ms, output truncated to first "
                        + (maxOutputBytes / 1024) + "KB)";
                return new ExecuteResponse(truncated, 124, true);
            }

            int exitCode = process.exitValue();
            String decoded = decodeOutput(rawBytes, isWindows);
            log.info("execute 完成: exitCode={}, elapsed={}ms, outputLen={}, outputHead=[{}]",
                    exitCode, elapsed, decoded.length(), decoded.length() > 200 ? decoded.substring(0, 200) + "..." : decoded);
            return new ExecuteResponse(decoded, exitCode, false);

        } catch (Exception e) {
            log.error("execute 异常: {}", e.getMessage(), e);
            return new ExecuteResponse(
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    1, false);
        }
    }

    /**
     * 注入编码相关的环境变量，让子进程优先用 UTF-8 输出。
     * 只设置未被用户显式覆盖的变量。
     */
    private void injectEncodingHints(Map<String, String> processEnv, boolean isWindows) {
        // Java 子进程：强制 UTF-8 文件编码
        processEnv.putIfAbsent("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8");

        // Python 子进程：UTF-8 模式 + I/O 编码
        processEnv.putIfAbsent("PYTHONUTF8", "1");
        processEnv.putIfAbsent("PYTHONIOENCODING", "utf-8");

        // Node.js：stderr/stdout 默认 UTF-8，设置 LANG 兜底
        processEnv.putIfAbsent("LANG", "en_US.UTF-8");
        processEnv.putIfAbsent("LC_ALL", "en_US.UTF-8");

        // Windows 控制台：强制 UTF-8 输出
        if (isWindows) {
            processEnv.putIfAbsent("PYTHONLEGACYWINDOWSSTDIO", "utf-8");
        }
    }

    /**
     * 智能解码命令输出：
     * <ul>
     *   <li>Windows：先尝试 UTF-8（chcp 65001 后应该是对的），
     *       若出现大量替换字符 U+FFFD，则降级用 GBK 解码（兼容未遵守 chcp 的子进程）</li>
     *   <li>非 Windows：直接 UTF-8</li>
     * </ul>
     */
    private String decodeOutput(byte[] raw, boolean isWindows) {
        if (raw == null || raw.length == 0) {
            return "";
        }

        // 硬截断：防止超长输出撑爆内存
        byte[] capped = raw;
        if (raw.length > maxOutputBytes) {
            capped = new byte[maxOutputBytes];
            System.arraycopy(raw, 0, capped, 0, maxOutputBytes);
        }

        String utf8Decoded = new String(capped, StandardCharsets.UTF_8);

        if (!isWindows) {
            return utf8Decoded;
        }

        // 检查 UTF-8 解码质量：如果替换字符超过 5%，说明实际是 GBK 编码
        int replacementCount = 0;
        for (int i = 0; i < utf8Decoded.length(); i++) {
            if (utf8Decoded.charAt(i) == '\uFFFD') {
                replacementCount++;
            }
        }
        double replacementRate = (double) replacementCount / Math.max(utf8Decoded.length(), 1);

        if (replacementRate > 0.05) {
            // GBK 解码兜底
            String gbkDecoded = new String(capped, WINDOWS_GBK);
            log.debug("Shell 输出 UTF-8 替换率 {:.1f}%，降级 GBK 解码", replacementRate * 100);
            return gbkDecoded;
        }

        return utf8Decoded;
    }
}
