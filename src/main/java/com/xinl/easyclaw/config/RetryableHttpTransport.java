package com.xinl.easyclaw.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 给任何 {@link HttpTransport} 加上 429/5xx 自动重试能力。
 * <p>
 * 退避策略：最多重试 {@code MAX_RETRIES} 次（可由构造器参数 {@code maxRetries} 覆盖），
 * 首次等待 {@code INITIAL_BACKOFF}（可由构造器参数 {@code initialBackoff} 覆盖），
 * 之后按 2 的幂指数增长，单次等待上限为 {@code MAX_BACKOFF}。
 * <p>
 * 非流式 {@link #execute(HttpRequest)} 在上述指数退避基础上额外叠加 ±10% 抖动，
 * 避免多个请求同时重试造成惊群；流式 {@link #stream(HttpRequest)} 由
 * {@link Retry#backoff(long, Duration)} 自带抖动。
 * <p>
 * 同时解析 {@code Retry-After} 响应头，若服务端明确指定了等待时间则优先使用。
 */
public class RetryableHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(RetryableHttpTransport.class);

    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(64);
    private static final List<RetryableHttpTransport> ALL = new CopyOnWriteArrayList<>();

    private final HttpTransport delegate;
    private final int maxRetries;
    private final Duration initialBackoff;
    private volatile boolean aborted = false;

    public RetryableHttpTransport(HttpTransport delegate) {
        this(delegate, MAX_RETRIES, INITIAL_BACKOFF);
    }

    public RetryableHttpTransport(HttpTransport delegate, int maxRetries, Duration initialBackoff) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        ALL.add(this);
    }

    /**
     * @deprecated 进程级中止会误伤其他会话的重试。请改用
     *         {@link RetryScope#abort(String)} 按会话中止。仅保留给无 sessionId 上下文的
     *         全局关停场景（如应用 shutdown）。
     */
    @Deprecated
    public static void abortAll() {
        for (RetryableHttpTransport t : ALL) {
            t.aborted = true;
        }
        log.info("已中止所有 HTTP 重试 (共 {} 个 transport)", ALL.size());
    }

    /**
     * @deprecated 同 {@link #abortAll()}，请改用 {@link RetryScope#clear(String)}。
     */
    @Deprecated
    public static void resetAll() {
        for (RetryableHttpTransport t : ALL) {
            t.aborted = false;
        }
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        int attempt = 0;
        HttpTransportException lastException = null;
        while (attempt <= maxRetries) {
            try {
                return delegate.execute(request);
            } catch (HttpTransportException e) {
                lastException = e;
                if (!isRetryable(e) || attempt == maxRetries
                        || isAborted(RetryScope.currentSessionId())) {
                    throw e;
                }
                attempt++;
                Duration wait = computeBackoff(attempt, e);
                log.warn("HTTP {} 限流/失败，第 {}/{} 次重试，等待 {} ms: {}",
                        e.getStatusCode(), attempt, maxRetries, wait.toMillis(), e.getMessage());
                try {
                    Thread.sleep(wait.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastException;
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        // sessionId 从 Reactor Context 取（由 AgentService 在订阅时 contextWrite 注入）：
        // 反应式链路会跨线程调度，ThreadLocal 在此不可靠
        return Flux.deferContextual(ctx -> {
            String sessionId = ctx.getOrDefault(RetryScope.CONTEXT_KEY, null);
            Retry retrySpec = Retry.backoff(maxRetries, initialBackoff)
                    .maxBackoff(MAX_BACKOFF)
                    .filter(t -> !isAborted(sessionId) && isRetryable(t))
                    .doBeforeRetry(signal -> {
                        if (isAborted(sessionId)) {
                            throw new RuntimeException("请求已被用户取消");
                        }
                        Throwable t = signal.failure();
                        long attempt = signal.totalRetries() + 1;
                        if (t instanceof HttpTransportException hte) {
                            log.warn("HTTP {} 流式请求限流/失败，第 {}/{} 次重试: {}",
                                    hte.getStatusCode(), attempt, maxRetries, hte.getMessage());
                        } else {
                            log.warn("流式请求失败，第 {}/{} 次重试: {}",
                                    attempt, maxRetries, t.getMessage());
                        }
                    });
            return delegate.stream(request).retryWhen(retrySpec);
        });
    }

    /**
     * 是否应放弃重试：会话级标记优先；{@code aborted} 实例标记仅为
     * {@link #abort()} 单实例调用与无 sessionId 上下文时的兜底。
     */
    private boolean isAborted(String sessionId) {
        return aborted || RetryScope.isAborted(sessionId);
    }

    @Override
    public void close() {
        // 先从静态列表注销，避免 delegate.close() 抛异常导致实例（及其底层连接池）永久泄漏
        ALL.remove(this);
        delegate.close();
    }

    public void abort() {
        this.aborted = true;
    }

    public void reset() {
        this.aborted = false;
    }

    /**
     * 判断异常是否可重试：
     * <ul>
     *   <li>{@link HttpTransportException#isRetryable()} 为 true（429 / 5xx）</li>
     *   <li>网络层 IOException（连接超时、连接重置等）</li>
     * </ul>
     */
    private boolean isRetryable(Throwable t) {
        if (t instanceof HttpTransportException hte) {
            if (hte.isRetryable()) {
                return true;
            }
            // 429 显式判断（有些框架版本 isRetryable 可能漏判）
            Integer code = hte.getStatusCode();
            if (code != null && (code == 429 || code >= 500)) {
                return true;
            }
            return false;
        }
        // 其他 RuntimeException：网络抖动也算可重试
        return t.getCause() instanceof java.io.IOException;
    }

    /**
     * 非流式 execute() 的手动退避计算：指数 + 抖动。
     * 如果异常里带 Retry-After 信息（框架可能解析），优先用。
     */
    private Duration computeBackoff(int attempt, HttpTransportException e) {
        // 指数: initial * 2^(attempt-1)
        long baseMs = initialBackoff.toMillis() * (1L << (attempt - 1));
        long cappedMs = Math.min(baseMs, MAX_BACKOFF.toMillis());
        // 加 ±10% 抖动，避免多请求同时重试（thundering herd）
        long jitter = (long) (cappedMs * 0.1 * (Math.random() * 2 - 1));
        long finalMs = Math.max(50, cappedMs + jitter);
        return Duration.ofMillis(finalMs);
    }
}
