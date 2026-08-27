package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.python.PythonSandbox;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skill 脚本执行工具。
 *
 * <p>Skill 可以在自己的目录下携带 {@code scripts/*.py}，由本工具在 Python 沙箱中执行。
 * 这样 skill 不再只能提供"文字规则"，而能提供可直接运行的确定性逻辑——
 * 需要精确结果的任务（统计、校验、格式转换）交给脚本比让模型逐行推演更可靠。
 *
 * <p><b>为什么不走 shell</b>：提示词里惯用的 {@code python3 scripts/x.py} 有两个问题——
 * Windows 上通常没有 {@code python3} 这个命令；且底层 shell 实现存在输出流死锁缺陷，
 * 脚本不退出会挂住整个会话。走 {@link PythonSandbox} 则自带超时强制中断、
 * 内存限额与目录隔离，失败模式可控。
 */
@Component
public class SkillScriptTools {

    private static final Logger log = LoggerFactory.getLogger(SkillScriptTools.class);

    /** 脚本执行超时。比 run_python 宽松：skill 脚本常做批量文件处理。 */
    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60);

    private final PythonSandbox pythonSandbox;

    public SkillScriptTools(PythonSandbox pythonSandbox) {
        this.pythonSandbox = pythonSandbox;
    }

    @Tool(name = "run_skill_script", description = "运行某个 Skill 自带的 Python 脚本（位于该 Skill 目录的 scripts/ 下），返回脚本输出。\n"
            + "【何时用】已加载的 Skill 提示你「有脚本可直接运行」时；需要确定性结果（统计、校验、批量转换）而不想手写逻辑时。\n"
            + "【不要用于】执行你自己临时写的代码（用 run_python）；读取脚本内容（用 read_file）。\n"
            + "【参数】skill：Skill 名称（目录名，如 clean-code）；script：scripts/ 下的脚本文件名（如 check.py）；args：传给脚本的参数列表，可留空。")
    public String runSkillScript(
            @ToolParam(name = "skill", description = "Skill 名称，即其目录名，例如 clean-code") String skill,
            @ToolParam(name = "script", description = "scripts/ 目录下的脚本文件名，例如 check_naming.py") String script,
            @ToolParam(name = "args", description = "传给脚本的命令行参数，按顺序排列；不需要时留空", required = false) List<String> args,
            WorkspaceContext workspace) {

        if (skill == null || skill.isBlank()) {
            return "❌ 未指定 skill 名称";
        }
        if (script == null || script.isBlank()) {
            return "❌ 未指定脚本文件名";
        }
        if (!pythonSandbox.isAvailable()) {
            return "❌ Python 执行环境不可用（当前 JVM 非 GraalVM），无法运行 Skill 脚本。";
        }

        Optional<Path> skillDir = resolveSkillDir(skill, workspace);
        if (skillDir.isEmpty()) {
            return "❌ 未找到 Skill: " + skill + "\n可用的 Skill: " + String.join("、", listSkillNames(workspace));
        }
        Path scriptsDir = skillDir.get().resolve("scripts");
        if (!Files.isDirectory(scriptsDir)) {
            return "❌ Skill「" + skill + "」没有 scripts/ 目录，不携带任何脚本。";
        }

        Path target;
        try {
            target = safeResolveScript(scriptsDir, script);
        } catch (SecurityException e) {
            return "❌ " + e.getMessage();
        }
        if (!Files.isRegularFile(target)) {
            return "❌ 脚本不存在: " + script + "\n该 Skill 可用脚本: " + String.join("、", listScripts(scriptsDir));
        }

        log.info("执行 Skill 脚本: skill={}, script={}, args={}", skill, script,
                args == null ? 0 : args.size());

        // allowedDir 给到 skill 根目录而非 scripts/：脚本常需读取同级的数据或规则文件
        PythonSandbox.Result result =
                pythonSandbox.executeScript(target, skillDir.get(), args, SCRIPT_TIMEOUT);
        return render(skill, script, result);
    }

    /**
     * 解析脚本路径并确保它没有逃出 scripts/ 目录。
     *
     * <p>脚本名来自模型输出，必须视为不可信输入：{@code ../../../etc/passwd} 这类
     * 相对路径若不校验就会把沙箱的目录隔离整个绕过。
     *
     * <p>字符串规范化之后还要按真实路径复查一次：脚本内容是用宿主 JVM 的
     * {@code Files.readString} 读的，不经沙箱的 FileSystem，所以 scripts/ 里一个
     * 指向外部的符号链接足以让任意文件被当作脚本执行。
     */
    private Path safeResolveScript(Path scriptsDir, String script) {
        Path base = scriptsDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(script).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("脚本路径越界，只能运行 scripts/ 目录内的脚本: " + script);
        }
        if (!resolved.getFileName().toString().endsWith(".py")) {
            throw new SecurityException("只能运行 .py 脚本: " + script);
        }
        try {
            Path realBase = base.toRealPath();
            Path realScript = resolved.toRealPath();
            if (!realScript.startsWith(realBase)) {
                throw new SecurityException(
                        "脚本路径越界（符号链接指向 scripts/ 之外）: " + script);
            }
            return realScript;
        } catch (IOException notFound) {
            // 文件不存在交给调用方的 isRegularFile 报更友好的错，这里不吞掉路径校验结论
            return resolved;
        }
    }

    /**
     * 按「workspace 覆盖 global」的既有约定查找 skill 目录。
     * <p>顺序与 system prompt 的 skill 装载顺序一致，避免出现"提示词用的是 A、
     * 执行到的是 B"这种难以排查的错位。
     */
    private Optional<Path> resolveSkillDir(String skill, WorkspaceContext workspace) {
        String name = Path.of(skill).getFileName().toString();
        for (Path root : skillRoots(workspace)) {
            Path candidate = root.resolve(name);
            if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("SKILL.md"))) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private List<Path> skillRoots(WorkspaceContext workspace) {
        List<Path> roots = new ArrayList<>();
        if (workspace != null && workspace.getPath() != null) {
            roots.add(workspace.getPath().resolve(".easyClaw/agent/skills"));
        }
        roots.add(SystemHomePaths.globalSkillsDir());
        return roots;
    }

    private List<String> listSkillNames(WorkspaceContext workspace) {
        List<String> names = new ArrayList<>();
        for (Path root : skillRoots(workspace)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.list(root)) {
                s.filter(Files::isDirectory)
                        .filter(p -> Files.exists(p.resolve("SKILL.md")))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(n -> {
                            if (!names.contains(n)) names.add(n);
                        });
            } catch (IOException ignored) {
                // 列举失败只影响错误提示的丰富度，不该让主流程报错
            }
        }
        return names;
    }

    private List<String> listScripts(Path scriptsDir) {
        try (Stream<Path> s = Files.list(scriptsDir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".py"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String render(String skill, String script, PythonSandbox.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.ok() ? "✅ " : "❌ ")
                .append(skill).append('/').append(script)
                .append(result.ok() ? " 执行成功（" : " 执行失败（")
                .append(result.millis()).append("ms）\n");
        if (!result.stdout().isBlank()) {
            sb.append("\n--- 输出 ---\n").append(result.stdout().stripTrailing()).append('\n');
        }
        if (!result.stderr().isBlank()) {
            sb.append("\n--- stderr ---\n").append(result.stderr().stripTrailing()).append('\n');
        }
        if (result.error() != null) {
            sb.append("\n--- 错误 ---\n").append(result.error().stripTrailing()).append('\n');
        }
        if (result.ok() && !result.hasOutput()) {
            sb.append("\n⚠️ 脚本执行完毕但没有任何输出，请确认脚本内使用了 print() 输出结果。");
        }
        return sb.toString();
    }
}
