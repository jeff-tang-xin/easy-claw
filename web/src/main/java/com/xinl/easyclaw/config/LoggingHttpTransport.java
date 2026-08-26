package com.xinl.easyclaw.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class LoggingHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingHttpTransport.class);

    private final HttpTransport delegate;

    public LoggingHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        logRequest(request);
        try {
            HttpResponse resp = delegate.execute(request);
            logResponse(resp);
            return resp;
        } catch (HttpTransportException e) {
            log.warn("[HTTP] {} {} -> {} ERROR: {}", request.getMethod(), request.getUrl(), e.getStatusCode(), e.getMessage());
            throw e;
        }
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        logRequest(request);
        return delegate.stream(request)
                .doOnError(t -> log.warn("[HTTP] {} {} stream ERROR: {}", request.getMethod(), request.getUrl(), t.getMessage()));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private void logRequest(HttpRequest req) {
        String url = req.getUrl();
        String body = req.getBody();
        // 截断 API key
        if (body != null) {
            body = body.replaceAll("(\"api_key\"\\s*:\\s*\")([^\"]{4})[^\"]*(\")", "$1$2***$3");
            body = body.replaceAll("(\"apiKey\"\\s*:\\s*\")([^\"]{4})[^\"]*(\")", "$1$2***$3");
        }
        log.info("[HTTP] {} {} body={}", req.getMethod(), url, body);
    }

    private void logResponse(HttpResponse resp) {
        String body = resp.getBody();
        if (body != null && body.length() > 500) {
            body = body.substring(0, 500) + "...(truncated)";
        }
        log.info("[HTTP] {} body={}", resp.getStatusCode(), body);
    }
}
