package com.xinl.easyclaw.python;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行模型生成的 Python 代码。
 *
 * <p><b>与 {@link GraalPyEngine} 的区别</b>：GraalPyEngine 调用的是随包发布的可信脚本
 * （{@code code_tools.py}），函数与参数都是我们自己写死的；本类执行的是<b>模型现场生成的
 * 任意代码</b>。信任级别完全不同，因此不能复用那套长生命周期的 ThreadLocal Context：
 *
 * <ul>
 *   <li>任意代码可能污染全局命名空间、可能死循环、可能耗尽内存；
 *   <li>一次执行的副作用不该泄漏到下一次执行。
 * </ul>
 *
 * <p>所以这里<b>每次执行都新建并销毁 Context</b>，代价是约 200ms 启动开销（实测数据见
 * {@code GraalPyCapabilityProbeTest}）。对于"跑一段计算"这种量级的操作，200ms 换取
 * 干净隔离与可中断性是划算的；这也是本类不做 Context 池化的原因。
 *
 * <p><b>沙箱边界</b>：
 *
 * <ul>
 *   <li>默认禁止所有 IO、网络、子进程、Java 互操作；
 *   <li>可选放开一个工作目录的读写（{@code allowedDir}），用于处理用户附件；
 *   <li>超时后强制中断（{@code Context.close(true)} 可打断死循环）；
 *   <li>stdout/stderr 捕获到内存并限制大小，防止刷爆上下文。
 * </ul>
 */
@Component
public class PythonSandbox {

    private static final Logger log = LoggerFactory.getLogger(PythonSandbox.class);

    /** 输出上限。超出即截断，保留头部——报错信息通常在前面。 */
    private static final int MAX_OUTPUT_CHARS = 32 * 1024;

    /** 单次执行的墙钟超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 单次执行的堆内存上限。
     *
     * <p>看门狗只能打断"跑得久"的代码，打断不了"一次性申请几个 G"的代码
     * （{@code bytearray(4 * 1024**3)} 会在看门狗醒来前就把 JVM 拖入 OOM，
     * 而 OOM 是进程级故障，会连带整个应用一起死）。因此必须有独立的内存限额。
     *
     * <p>取值 256MB：实测限额本身不带来启动开销（见 {@code PythonSandboxTest
     * .heapLimitDoesNotSlowDownStartup}），而 256MB 足够跑完常规数据处理，
     * 又远低于典型 JVM 堆，不至于让沙箱把宿主拖垮。
     */
    private static final String MAX_HEAP = "256MB";

    /** 运行时是否具备 Python 能力，启动时探测一次。 */
    private final AtomicBoolean available = new AtomicBoolean(false);

    private volatile boolean probed = false;

    /** 执行结果。 */
    public record Result(boolean ok, String stdout, String stderr, String error, long millis) {

        /** 是否有任何可展示内容。 */
        public boolean hasOutput() {
            return !stdout.isBlank() || !stderr.isBlank();
        }
    }

    /**
     * 探测当前运行时是否支持 Python。
     *
     * <p>惰性探测而非 {@code @PostConstruct}：本类不像 GraalPyEngine 那样需要预热
     * （每次都新建 Context，预热没有收益），只需知道能力是否存在。
     */
    public boolean isAvailable() {
        if (probed) {
            return available.get();
        }
        synchronized (this) {
            if (probed) {
                return available.get();
            }
            try (Context ctx = Context.newBuilder("python").build()) {
                ctx.eval("python", "1");
                available.set(true);
            } catch (Throwable t) {
                log.warn("当前运行时不支持 Python 沙箱（需 GraalVM）：{}", t.getMessage());
                available.set(false);
            }
            probed = true;
            return available.get();
        }
    }

    /**
     * 在沙箱中执行 Python 代码。
     *
     * @param code 待执行代码
     * @param allowedDir 允许读写的目录；null 表示完全禁止文件访问
     * @param timeout 超时时间；null 用默认值
     * @return 执行结果，永不抛异常
     */
    public Result execute(String code, Path allowedDir, Duration timeout) {
        if (!isAvailable()) {
            return new Result(false, "", "", "Python 运行时不可用（需 GraalVM）", 0);
        }
        Duration limit = timeout == null ? DEFAULT_TIMEOUT : timeout;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();

        Context ctx = buildContext(out, err, allowedDir);
        // 看门狗：Polyglot 没有内置执行超时，靠另一线程 close(true) 强制中断。
        // close(true) 会让被中断线程抛出 PolyglotException(isCancelled)。
        Thread watchdog = startWatchdog(ctx, limit);
        try {
            ctx.eval(Source.newBuilder("python", code, "<agent>").buildLiteral());
            return finish(true, out, err, null, start);
        } catch (PolyglotException e) {
            return finish(false, out, err, describe(e, limit), start);
        } catch (Exception e) {
            return finish(false, out, err, "执行失败: " + e.getMessage(), start);
        } finally {
            watchdog.interrupt();
            closeQuietly(ctx);
        }
    }

    /**
     * 把 PolyglotException 翻译成给模型看的原因说明。
     *
     * <p>分类必须区分清楚：超时要提示"死循环已被终止"，资源超限要提示"换算法"，
     * guest 异常则原样给出 Python traceback——三者对模型的下一步指引完全不同。
     */
    private String describe(PolyglotException e, Duration limit) {
        // 资源超限必须排在取消/中断之前判断：超出堆限额时 Polyglot 会中止执行，
        // 该异常同时带上 cancelled 标记，若先判 cancelled 就会把 OOM 误报成"超时"，
        // 把用户引向"加大超时时间"这个错误方向。
        if (e.isResourceExhausted()) {
            return "资源超限（内存上限 " + MAX_HEAP + "）：" + e.getMessage()
                    + "\n请改用分块处理或减小数据量后重试。";
        }
        if (e.isCancelled() || e.isInterrupted()) {
            return "执行超时（超过 " + limit.toSeconds() + " 秒），已强制终止";
        }
        if (e.isGuestException()) {
            // Python 侧异常：guest 的 traceback 比 Java 消息有用得多
            return e.getMessage();
        }
        return "执行失败: " + e.getMessage();
    }

    /**
     * 在沙箱中执行位于 {@code scriptFile} 的 Python 脚本（脚本目录只读）。
     *
     * <p>等价于 {@code executeScript(scriptFile, ScriptAccess.readOnly(allowedDir), args, timeout)}
     * ——即<b>不给任何写权限</b>。默认只读是刻意的：脚本能写自身目录就能改写同目录的
     * {@code SKILL.md}，而该文件会作为 system prompt 注入后续会话。
     */
    public Result executeScriptReadOnly(Path scriptFile, Path allowedDir, List<String> args, Duration timeout) {
        if (allowedDir == null) {
            // 保持与旧签名一致的错误返回，而不是让 ScriptAccess 的构造校验抛异常
            return new Result(false, "", "", "执行脚本必须指定允许访问的目录", 0);
        }
        return executeScript(scriptFile, ScriptAccess.readOnly(allowedDir), args, timeout);
    }

    /**
     * 在沙箱中执行 Python 脚本，文件权限由 {@link ScriptAccess} 描述。
     *
     * <p>与 {@link #execute} 的区别在于脚本以<b>文件</b>而非字符串求值，因此
     * {@code __name__ == "__main__"} 成立、{@code sys.argv} 可用（实测确认），
     * 普通 Python 脚本无需改写即可运行。
     *
     * <p><b>读写范围分离</b>：可读集是 {@code baseDir + readableDirs + writableDir} 的并集，
     * 可写集只有 {@code writableDir}。落在可读集之外的路径一律拒绝。
     *
     * @param scriptFile 脚本路径，必须位于 {@code access.baseDir()} 之内
     * @param access 文件访问权限（非 null）
     * @param args 传给脚本的参数，映射为 {@code sys.argv[1:]}
     * @param timeout 超时时间；null 用默认值
     */
    public Result executeScript(Path scriptFile, ScriptAccess access, List<String> args, Duration timeout) {
        if (!isAvailable()) {
            return new Result(false, "", "", "Python 运行时不可用（需 GraalVM）", 0);
        }
        if (access == null) {
            return new Result(false, "", "", "执行脚本必须指定允许访问的目录", 0);
        }
        Duration limit = timeout == null ? DEFAULT_TIMEOUT : timeout;
        // 文档契约必须由代码保证：脚本内容用宿主 JVM 的 Files.readString 读取，
        // 完全不经过 RestrictedFileSystem，所以这里是唯一的边界检查点。
        // 少了它，调用方传入任意路径就能把沙箱外的文件当脚本执行。
        Path script;
        try {
            Path realAllowed = access.baseDir().toRealPath();
            script = scriptFile.toRealPath();
            if (!script.startsWith(realAllowed)) {
                return new Result(false, "", "", "脚本不在允许访问的目录内: " + scriptFile, 0);
            }
        } catch (java.io.IOException e) {
            return new Result(false, "", "", "无法解析脚本路径: " + e.getMessage(), 0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();

        // argv[0] 按 Python 惯例是脚本名本身
        List<String> argv = new java.util.ArrayList<>();
        argv.add(script.getFileName().toString());
        if (args != null) {
            argv.addAll(args);
        }

        Context ctx = buildContext(out, err, access, argv);
        Thread watchdog = startWatchdog(ctx, limit);
        try {
            String code = java.nio.file.Files.readString(script, StandardCharsets.UTF_8);
            // 用脚本文件名作为 Source name，traceback 里才会显示真实文件名而非 <agent>
            ctx.eval(Source.newBuilder("python", code, script.getFileName().toString())
                    .buildLiteral());
            return finish(true, out, err, null, start);
        } catch (PolyglotException e) {
            // 资源超限必须先判：describe() 刻意把 isResourceExhausted() 排在最前，
            // 若让 isExit() 抢先，堆超限就会被误报成"退出码 N"，丢掉真正的原因。
            if (e.isResourceExhausted()) {
                return finish(false, out, err, describe(e, limit), start);
            }
            // 脚本以 sys.exit() 结束是完全正常的写法（本项目自带脚本就这么写），
            // Polyglot 把它表达为 isExit() 的异常。退出码 0 必须算成功，
            // 否则所有规范写法的脚本都会被判失败。
            if (e.isExit()) {
                int status = e.getExitStatus();
                if (status == 0) {
                    return finish(true, out, err, null, start);
                }
                // 非零退出多数是脚本在报告业务结论（如"发现 3 处问题"、用法提示），
                // 不是执行故障；措辞不能归因成"执行失败"，否则调用方会误判工具坏了。
                return finish(false, out, err,
                        "脚本以状态码 " + status + " 结束（属脚本自身的返回结论，非执行故障，详见上方输出）",
                        start);
            }
            return finish(false, out, err, describe(e, limit), start);
        } catch (java.io.IOException e) {
            return finish(false, out, err, "无法读取脚本文件: " + e.getMessage(), start);
        } catch (Exception e) {
            return finish(false, out, err, "执行失败: " + e.getMessage(), start);
        } finally {
            watchdog.interrupt();
            closeQuietly(ctx);
        }
    }

    /**
     * 构建受限 Context。
     *
     * <p>白名单式放权：默认全禁，只放开明确需要的。这样新增的 polyglot 能力
     * 不会因为默认开启而意外可用。
     *
     * <p>此重载用于 {@code run_python}：{@code allowedDir} 即工作目录，可读可写。
     * 模型现场生成的代码本就以"处理附件"为目的，需要写权限。
     */
    private Context buildContext(ByteArrayOutputStream out, ByteArrayOutputStream err, Path allowedDir) {
        if (allowedDir == null) {
            return buildContext(out, err, (ScriptAccess) null, null);
        }
        return buildContext(out, err, ScriptAccess.readOnly(allowedDir).withWritable(allowedDir), null);
    }

    /**
     * Python 标准库所在目录，作为沙箱的只读根放行。
     *
     * <p>限制文件访问后 {@code import ast} 之类会直接失败（实测
     * {@code ModuleNotFoundError}）——标准库位于 GraalPy 的 python-home，
     * 不在用户目录里。这里探测一次并缓存：探测需要新建 Context，约 200ms，
     * 不该每次执行都付这个成本。
     *
     * <p>只读放行不削弱隔离：写操作由 {@link RestrictedFileSystem} 在这些根内一律拒绝，
     * 脚本无法篡改标准库来影响后续执行。
     */
    private List<Path> stdlibRoots() {
        List<Path> cached = stdlibRootsCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (stdlibRootsCache == null) {
                // 只缓存成功结果（probeStdlibRoots 失败返回 null），失败下次重试
                stdlibRootsCache = probeStdlibRoots();
            }
            return stdlibRootsCache == null ? List.of() : stdlibRootsCache;
        }
    }

    private List<Path> probeStdlibRoots() {
        // 探测 Context 只求值硬编码的 sys.prefix，不需要文件访问权限。
        // 刻意不用 IOAccess.ALL：本类的设计原则是最小放权，探测口子一旦开成全权限，
        // 后续往这里加逻辑就会默认继承它。
        try (Context probe = Context.newBuilder("python")
                .option("python.PosixModuleBackend", "java")
                .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
                .build()) {
            String prefix = probe.eval("python", "import sys\nsys.prefix").asString();
            if (prefix == null || prefix.isBlank()) {
                return null;
            }
            // 在此处一次性归一化为真实路径：该值进程内恒定，
            // 否则每次脚本执行都要为标准库目录重做一次 toRealPath 系统调用。
            return List.of(Path.of(prefix).toRealPath());
        } catch (Exception e) {
            // 探测失败不致命：沙箱仍可运行不 import 标准库的脚本。
            // 返回 null 而非空列表，让下次执行重试——把一次偶发失败缓存成
            // 整个 JVM 生命周期内标准库都不可用，是极难排查的故障。
            log.warn("探测 Python 标准库目录失败，本次脚本将无法 import 标准库: {}", e.getMessage());
            return null;
        }
    }

    private volatile List<Path> stdlibRootsCache;

    private Context buildContext(ByteArrayOutputStream out, ByteArrayOutputStream err,
                                 ScriptAccess access, List<String> argv) {
        Path allowedDir = access == null ? null : access.baseDir();
        Context.Builder builder = Context.newBuilder("python")
                .out(out)
                .err(err)
                // 不给 stdin：input() 应立即失败，而不是挂住整个执行
                .in(java.io.InputStream.nullInputStream())
                // 以下三项是沙箱的核心：禁止触达宿主 JVM 与操作系统
                .allowHostAccess(org.graalvm.polyglot.HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .allowExperimentalOptions(true)
                // 内存限额：实测在当前运行时真实生效（超限抛 PolyglotException 而非 OOM）。
                // 注意"选项被接受"不等于"限额被执行"——本项目靠 PythonSandboxTest
                // .rejectsExcessiveMemoryAllocation 实际申请超额内存来验证它真的拦得住。
                .option("sandbox.MaxHeapMemory", MAX_HEAP)
                .option("python.PosixModuleBackend", "java");

        if (argv != null && !argv.isEmpty()) {
            // 实测确认：arguments() 后脚本内 sys.argv 可读、__name__ == "__main__" 成立
            builder.arguments("python", argv.toArray(new String[0]));
        }

        if (allowedDir == null) {
            // 完全禁止文件访问
            builder.allowIO(org.graalvm.polyglot.io.IOAccess.NONE);
        } else {
            // 用 RestrictedFileSystem 强制限制在单目录内。
            // 注意：只设 currentWorkingDirectory 是无效的——实测（PythonSandboxTest
            // .cannotEscapeAllowedDir）证明 Python 能用绝对路径读到沙箱外的文件，
            // cwd 只影响相对路径解析，不是安全边界。
            try {
                // 只读根 = Python 标准库 + 调用方额外声明的可读目录（如被扫描的工作区）
                List<Path> readOnly = new java.util.ArrayList<>(stdlibRoots());
                readOnly.addAll(access.readableDirs());
                builder.allowIO(org.graalvm.polyglot.io.IOAccess.newBuilder()
                        .fileSystem(new RestrictedFileSystem(
                                allowedDir, readOnly, access.writableDirs(), access.deniedDirs()))
                        .build())
                        .currentWorkingDirectory(allowedDir.toAbsolutePath());
            } catch (java.io.IOException e) {
                // 无法解析目录真实路径时宁可禁用 IO，不可退化为无限制访问
                log.warn("无法为 {} 建立受限文件系统，已禁用文件访问: {}", allowedDir, e.getMessage());
                builder.allowIO(org.graalvm.polyglot.io.IOAccess.NONE);
            }
        }
        return builder.build();
    }

    private Thread startWatchdog(Context ctx, Duration limit) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(limit.toMillis());
                // 睡满说明主线程还没执行完，强制中断
                ctx.close(true);
            } catch (InterruptedException expected) {
                // 正常完成路径：主线程执行完后 interrupt 了看门狗
            } catch (Exception ignore) {
                // Context 可能已被主线程关闭，属正常竞态
            }
        }, "python-sandbox-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Result finish(boolean ok, ByteArrayOutputStream out, ByteArrayOutputStream err,
                          String error, long start) {
        return new Result(
                ok,
                truncate(out.toString(StandardCharsets.UTF_8)),
                truncate(err.toString(StandardCharsets.UTF_8)),
                error,
                System.currentTimeMillis() - start);
    }

    private String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n...[输出超过 " + MAX_OUTPUT_CHARS + " 字符，已截断]";
    }

    private void closeQuietly(Context ctx) {
        try {
            ctx.close(true);
        } catch (Exception ignore) {
            // 已关闭或正在关闭，无需处理
        }
    }
}
