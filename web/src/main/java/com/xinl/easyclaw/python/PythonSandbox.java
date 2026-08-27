package com.xinl.easyclaw.python;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
            String reason;
            if (e.isCancelled() || e.isInterrupted()) {
                reason = "执行超时（超过 " + limit.toSeconds() + " 秒），已强制终止";
            } else if (e.isGuestException()) {
                // Python 侧异常：guest 的 traceback 比 Java 消息有用得多
                reason = e.getMessage();
            } else {
                reason = "执行失败: " + e.getMessage();
            }
            return finish(false, out, err, reason, start);
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
     */
    private Context buildContext(ByteArrayOutputStream out, ByteArrayOutputStream err, Path allowedDir) {
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
                .option("python.PosixModuleBackend", "java");

        if (allowedDir == null) {
            // 完全禁止文件访问
            builder.allowIO(org.graalvm.polyglot.io.IOAccess.NONE);
        } else {
            // 用 RestrictedFileSystem 强制限制在单目录内。
            // 注意：只设 currentWorkingDirectory 是无效的——实测（PythonSandboxTest
            // .cannotEscapeAllowedDir）证明 Python 能用绝对路径读到沙箱外的文件，
            // cwd 只影响相对路径解析，不是安全边界。
            try {
                builder.allowIO(org.graalvm.polyglot.io.IOAccess.newBuilder()
                        .fileSystem(new RestrictedFileSystem(allowedDir))
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
