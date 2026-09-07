package com.xinl.easyclaw.python;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * GraalPy 执行引擎：为工具层提供受控的 Python 调用能力。
 *
 * <p>设计依据来自 {@code GraalPyCapabilityProbeTest} 的实测数据：
 *
 * <ul>
 *   <li>新建一个 Context 约 197ms，首次 {@code import difflib, ast} 约 1095ms；
 *   <li>复用同一 Context 连续 20 次 eval 仅 5ms（摊薄后约 0.25ms/次）。
 * </ul>
 *
 * <p>因此必须复用 Context 并在启动时预热，绝不能每次调用新建。
 *
 * <p><b>线程安全</b>：Polyglot {@code Context} 不允许多线程并发进入。这里采用
 * ThreadLocal 为每个调用线程持有独立 Context，避免加锁串行化成为瓶颈；
 * 代价是每个新线程首次调用需付一次初始化开销，故线程池规模应保持稳定。
 *
 * <p><b>降级</b>：非 GraalVM 运行时（例如系统默认的 JDK 17）无法创建 python
 * Context。此时 {@link #isAvailable()} 返回 false，调用方应回落到 Java 实现，
 * 而不是让整个工具失效。
 */
@Component
public class GraalPyEngine {

    private static final Logger log = LoggerFactory.getLogger(GraalPyEngine.class);

    /** Python 侧实现所在的 classpath 位置。 */
    private static final String SCRIPT_PATH = "python/code_tools.py";

    /** 单次 Python 调用的输出上限，防止异常脚本产出超大字符串冲爆内存与模型上下文。 */
    private static final int MAX_RESULT_CHARS = 512 * 1024;

    /** Python 源码，启动时读入一次，供各线程 Context 初始化复用。 */
    private volatile String scriptSource;

    /** 引擎整体是否可用（Python 运行时存在且脚本加载成功）。 */
    private final AtomicBoolean available = new AtomicBoolean(false);

    /**
     * 每线程独立的 Context。
     *
     * <p>不用 {@code ThreadLocal.withInitial}，因为初始化可能失败，需要区分
     * “未初始化”与“初始化失败”两种状态并记录日志。
     */
    private final ThreadLocal<Context> threadContext = new ThreadLocal<>();

    /**
     * 初始化并预热引擎。
     *
     * <p>由 Spring 在容器启动时自动调用；在非 Spring 场景（如单元测试）中
     * 需手动调用一次。可重复调用，重复调用不会重复预热。
     */
    @PostConstruct
    public void init() {
        if (available.get()) {
            return;
        }
        try {
            scriptSource = loadScript();
        } catch (IOException e) {
            log.error("加载 Python 脚本 {} 失败，Python 增强工具将降级为 Java 实现", SCRIPT_PATH, e);
            return;
        }

        // 启动阶段做一次完整验证并预热：把约 1.1s 的 stdlib import 开销挪到启动期，
        // 避免第一个真实请求承担这个延迟。
        long start = System.currentTimeMillis();
        try {
            Context probe = createContext();
            try {
                Value fn = probe.getBindings("python").getMember("unified_diff");
                if (fn == null || !fn.canExecute()) {
                    log.error("Python 脚本已加载但未导出 unified_diff 函数，降级为 Java 实现");
                    probe.close(true);
                    return;
                }
                available.set(true);
                log.info("GraalPy 引擎就绪，预热耗时 {}ms", System.currentTimeMillis() - start);
            } finally {
                // 预热用的 Context 属于当前线程，直接复用，避免浪费这次初始化
                threadContext.set(probe);
            }
        } catch (Throwable t) {
            // 非 GraalVM 运行时会在此抛出（IllegalArgumentException: language python not installed）。
            // 这是预期内的降级路径，不应让应用启动失败，故只记警告。
            log.warn(
                    "无法初始化 GraalPy（当前 JVM 可能不是 GraalVM），Python 增强工具将降级为 Java 实现：{}",
                    t.getMessage());
        }
    }

    /** 引擎是否可用。调用方据此决定走 Python 实现还是 Java 兜底实现。 */
    public boolean isAvailable() {
        return available.get();
    }

    /**
     * 调用 Python 模块中的顶层函数。
     *
     * @param function 函数名，必须是脚本中已定义的顶层函数
     * @param args 参数，仅支持可直接映射的标量类型（String / 数字 / 布尔）
     * @return 函数返回的字符串（约定为 JSON）
     * @throws PythonUnavailableException 引擎不可用时抛出，调用方应捕获并降级
     * @throws PythonExecutionException 脚本执行出错时抛出
     */
    public String call(String function, Object... args) {
        if (!available.get()) {
            throw new PythonUnavailableException("GraalPy 引擎不可用");
        }
        Context ctx = obtainContext();
        try {
            Value fn = ctx.getBindings("python").getMember(function);
            if (fn == null || !fn.canExecute()) {
                throw new PythonExecutionException("Python 函数不存在或不可调用: " + function);
            }
            Value result = fn.execute(args);
            if (result == null || result.isNull()) {
                throw new PythonExecutionException("Python 函数 " + function + " 返回空值");
            }
            if (!result.isString()) {
                throw new PythonExecutionException(
                        "Python 函数 " + function + " 应返回字符串，实际为: " + result.getMetaObject());
            }
            String text = result.asString();
            if (text.length() > MAX_RESULT_CHARS) {
                throw new PythonExecutionException(
                        "Python 返回内容超出上限 " + MAX_RESULT_CHARS + " 字符，已拒绝");
            }
            return text;
        } catch (PolyglotException e) {
            // 脚本内部异常（含语法/运行时错误）。若属于引擎级故障则丢弃 Context 重建，
            // 避免后续调用持续失败在同一个坏上下文上。
            if (e.isInternalError() || e.isExit()) {
                discardContext(ctx);
            }
            throw new PythonExecutionException("Python 执行失败: " + e.getMessage(), e);
        }
    }

    /** 取当前线程的 Context，不存在则创建。 */
    private Context obtainContext() {
        Context ctx = threadContext.get();
        if (ctx != null) {
            return ctx;
        }
        long start = System.currentTimeMillis();
        ctx = createContext();
        threadContext.set(ctx);
        log.debug(
                "为线程 {} 创建 Python Context，耗时 {}ms",
                Thread.currentThread().getName(),
                System.currentTimeMillis() - start);
        return ctx;
    }

    /**
     * 创建并初始化一个 Context。
     *
     * <p>权限说明：{@code allowAllAccess(false)} + 仅放开 IO 之外的必要权限。
     * 本模块脚本是随包发布的固定代码、不执行外部输入，且不做 IO/网络/进程操作，
     * 因此无需 {@code allowAllAccess(true)}。这样即便脚本被篡改也无法越权。
     */
    private Context createContext() {
        Context ctx =
                Context.newBuilder("python")
                        // 纯 Python stdlib（ast/difflib）需要读取语言自带资源，
                        // 放开 hostClassLookup 之外的最小集合即可
                        .allowExperimentalOptions(true)
                        .option("python.PosixModuleBackend", "java")
                        .build();
        ctx.eval("python", scriptSource);
        return ctx;
    }

    /** 丢弃损坏的 Context，下次调用会自动重建。 */
    private void discardContext(Context ctx) {
        threadContext.remove();
        try {
            ctx.close(true);
        } catch (Exception ignore) {
            // 关闭失败无需处理，交由 GC 回收
        }
    }

    private String loadScript() throws IOException {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @PreDestroy
    void shutdown() {
        Context ctx = threadContext.get();
        if (ctx != null) {
            discardContext(ctx);
        }
        available.set(false);
    }

    /** 引擎不可用（非 GraalVM 环境或脚本加载失败）。调用方应据此降级。 */
    public static class PythonUnavailableException extends RuntimeException {
        public PythonUnavailableException(String message) {
            super(message);
        }
    }

    /** Python 脚本执行出错。 */
    public static class PythonExecutionException extends RuntimeException {
        public PythonExecutionException(String message) {
            super(message);
        }

        public PythonExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
