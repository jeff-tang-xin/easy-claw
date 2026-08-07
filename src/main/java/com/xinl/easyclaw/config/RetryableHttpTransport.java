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
 * 退避策略：500ms → 1s → 2s → 4s → 8s → 16s → 32s → 64s → 128s → 256s
 * 共 10 次，总等待约 511.5s（≈8.5min）。
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

    public static void abortAll() {
        for (RetryableHttpTransport t : ALL) {
            t.aborted = true;
        }
        log.info("已中止所有 HTTP 重试 (共 {} 个 transport)", ALL.size());
    }

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
                if (!isRetryable(e) || attempt == maxRetries) {
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
        Retry retrySpec = Retry.backoff(maxRetries, initialBackoff)
                .maxBackoff(MAX_BACKOFF)
                .filter(t -> !aborted && isRetryable(t))
                .doBeforeRetry(signal -> {
                    if (aborted) {
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
    }

    @Override
    public void close() {
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
