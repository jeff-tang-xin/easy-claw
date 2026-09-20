package com.xinl.easyclaw.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

/**
 * 不关闭底层连接池的 transport 装饰器。
 * <p>
 * 默认 transport（{@link io.agentscope.core.model.transport.OkHttpTransport}）是跨所有模型
 * 共享的单例。其 {@code close()} 会真正 {@code shutdown()} 掉 OkHttp 的调度线程池并清空连接池，
 * 一旦某个模型/会话级包装器（如 {@link RetryableHttpTransport}）在生命周期结束时透传调用，
 * 会把其他模型仍在使用的共享客户端一并废掉。而 JDK 版 transport 的 {@code close()} 是空操作，
 * 切换到 OkHttp 后必须补上这层防护。
 * <p>
 * 这里把 {@link #close()} 降级为 no-op，共享连接池只在 JVM 关闭时由 vendored 的
 * {@code AgentScopeJvmShutdownHook} 统一释放。
 */
public class NonClosingHttpTransport implements HttpTransport {

    private final HttpTransport delegate;

    public NonClosingHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(request);
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(request);
    }

    @Override
    public void close() {
        // 共享单例：禁止单个调用方关闭底层连接池
    }
}
