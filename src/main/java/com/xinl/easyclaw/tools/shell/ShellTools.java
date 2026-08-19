package com.xinl.easyclaw.tools.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台 Shell 工具。
 * <p>
 * 提供命令执行能力，供 VerifierAgent 等需要编译/测试验证的智能体调用。
 * <p>
 * 安全限制：
 * <ul>
 *   <li>工作目录强制限定在 workspaceRoot 下（防止越权访问）</li>
 *   <li>单次执行超时可配置（默认 180s，编译 300s，测试 600s）</li>
 *   <li>输出截断 8000 字符（防止上下文爆炸）</li>
 *   <li>Windows 用 cmd /c，Unix 用 sh -c</li>
 * </ul>
 */
@Component
public class ShellTools {

    private static final Logger log = LoggerFactory.getLogger(ShellTools.class);
    private static final int MAX_OUTPUT = 8000;
    private static final long DEFAULT_TIMEOUT_SEC = 180;
    private static final long COMPILE_TIMEOUT_SEC = 300;
    private static final long TEST_TIMEOUT_SEC = 600;

    private final String workspaceRoot;
    private final boolean isWindows;

    public ShellTools(
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().toString();
        this.isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        log.info("ShellTools 初始化: workspaceRoot={}, isWindows={}", this.workspaceRoot, isWindows);
    }

    /**
     * 执行任意 shell 命令（在工作区目录内）。
     * Windows: cmd /c <command>
     * Unix:    sh -c <command>
     *
     * @param command 完整命令字符串（如 "mvn compile -q" 或 "dir" / "ls -la"）
     * @return 命令输出（stdout + stderr），含退出码
     */
    public String runCommand(String command) {
        return executeShell(command, workspaceRoot, DEFAULT_TIMEOUT_SEC);
    }

    /**
     * 在指定子目录内执行命令。
     */
    public String runCommand(String command, String relativePath) {
        Path resolved = resolveSafe(relativePath);
        if (resolved == null) {
            return "❌ 非法路径: " + relativePath + "（必须在工作区内）";
        }
        return executeShell(command, resolved.toString(), DEFAULT_TIMEOUT_SEC);
    }

    /**
     * 验证 Maven 项目能否编译通过（mvn compile）。
     *
     * @param projectPath 项目根目录（相对工作区的路径，如 "." 或 "submodule"）
     * @return 编译结果：成功返回编译摘要，失败返回错误信息
     */
    public String verifyMavenCompile(String projectPath) {
        Path resolved = resolveSafe(projectPath);
        if (resolved == null) {
            return "❌ 非法路径: " + projectPath + "（必须在工作区内）";
        }
        String result = executeShell(
                isWindows ? "mvn compile -q -DskipTests" : "mvn compile -q -DskipTests",
                resolved.toString(), COMPILE_TIMEOUT_SEC);
        return interpretMavenResult(result, "编译");
    }

    /**
     * 运行 Maven 测试（mvn test）。
     *
     * @param projectPath 项目根目录（相对工作区的路径）
     * @return 测试结果摘要
     */
    public String runMavenTests(String projectPath) {
        Path resolved = resolveSafe(projectPath);
        if (resolved == null) {
            return "❌ 非法路径: " + projectPath + "（必须在工作区内）";
        }
        String result = executeShell("mvn test -q", resolved.toString(), TEST_TIMEOUT_SEC);
        return interpretMavenResult(result, "测试");
    }

    /**
     * 执行 shell 命令的核心实现。
     */
    private String executeShell(String command, String cwd, long timeoutSec) {
        String[] cmdArray = isWindows
                ? new String[]{"cmd", "/c", command}
                : new String[]{"sh", "-c", command};

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(Path.of(cwd).toFile());
        pb.redirectErrorStream(true);

        Process process = null;
        try {
            long start = System.currentTimeMillis();
            process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() + 1 > MAX_OUTPUT) {
                        output.append("\n... (输出已截断，超过 ").append(MAX_OUTPUT).append(" 字符)");
                        break;
                    }
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Shell 命令超时（{}s），已终止: cmd={}, cwd={}", timeoutSec, command, cwd);
                return "❌ 命令超时（" + timeoutSec + "s），已终止\n命令: " + command
                        + "\n部分输出:\n" + output;
            }

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - start;
            log.info("Shell 命令完成: exit={}, elapsed={}ms, cmd={}, cwd={}",
                    exitCode, elapsed, command, cwd);

            StringBuilder result = new StringBuilder();
            result.append("退出码: ").append(exitCode);
            result.append(" | 耗时: ").append(elapsed).append("ms");
            result.append(" | 命令: ").append(command);
            result.append("\n--- 输出 ---\n");
            result.append(output.length() == 0 ? "(无输出)" : output);
            return result.toString();

        } catch (IOException e) {
            log.error("Shell 执行 IO 异常: cmd={}, err={}", command, e.getMessage());
            return "❌ IO 异常: " + e.getMessage() + "\n命令: " + command;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Shell 执行被中断: cmd={}", command);
            return "❌ 执行被中断\n命令: " + command;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 路径安全检查：确保 projectPath 解析后在 workspaceRoot 之内。
     */
    private Path resolveSafe(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            projectPath = ".";
        }
        Path base = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Path resolved = base.resolve(projectPath).normalize();
        if (!resolved.startsWith(base)) {
            log.warn("路径越权访问被拒绝: projectPath={}, resolved={}", projectPath, resolved);
            return null;
        }
        return resolved;
    }

    /**
     * 解读 Maven 命令的输出，给出友好的成功/失败提示。
     */
    private String interpretMavenResult(String rawOutput, String phaseName) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "⚠️ " + phaseName + "无输出";
        }
        if (rawOutput.contains("❌")) {
            return rawOutput;
        }
        boolean hasExitCode = rawOutput.contains("退出码: ");
        boolean success = false;
        if (hasExitCode) {
            String exitCodeStr = rawOutput.substring(rawOutput.indexOf("退出码: ") + 4);
            exitCodeStr = exitCodeStr.substring(0, Math.min(exitCodeStr.indexOf(' '), exitCodeStr.length()));
            try {
                success = Integer.parseInt(exitCodeStr) == 0;
            } catch (NumberFormatException ignored) {}
        }

        StringBuilder result = new StringBuilder();
        if (success) {
            result.append("✅ ").append(phaseName).append("通过\n\n");
        } else {
            result.append("❌ ").append(phaseName).append("失败\n\n");
        }
        result.append(rawOutput);
        return result.toString();
    }
}
