package com.xinl.easyclaw.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.regex.Pattern;

public class LoggingHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingHttpTransport.class);

    /**
     * 请求体日志的截断长度。请求体含完整历史消息 + 工具定义，实测可达数百 KB；
     * 原先响应体截断到 500 字符而请求体全量打印，是明显的遗漏。
     * <p>
     * 全量打印的代价不只是磁盘：IDE 控制台同步输出超长字符串比写文件更慢，
     * 而这发生在每一次 LLM 请求的关键路径上。
     */
    private static final int MAX_BODY_LOG_LEN = 500;

    /**
     * body 内凭证字段的脱敏正则，预编译避免每次请求重新编译。
     * <p>
     * 注意：OpenAI 兼容协议的 key 走 {@code Authorization} 请求头，正常请求体里
     * <b>没有</b>这两个字段，因此这两条规则平时不会命中。保留它们是为了兜住
     * 自定义 provider / 网关把凭证塞进 body 的情况——脱敏漏一次的代价远大于
     * 两次正则匹配。
     * <p>
     * <b>必须在截断之前执行</b>：截断点若落在 key 值中间会切掉闭合引号，
     * 使这两条正则失配，密钥原文直接进日志。
     */
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(\"api_key\"\\s*:\\s*\")([^\"]{4})[^\"]*(\")");
    private static final Pattern CAMEL_API_KEY_PATTERN =
            Pattern.compile("(\"apiKey\"\\s*:\\s*\")([^\"]{4})[^\"]*(\")");

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
        if (!log.isInfoEnabled()) {
            return;
        }
        // 【先脱敏再截断】顺序不能颠倒：截断可能把 api_key 的闭合引号切掉，
        // 导致脱敏正则 (\"api_key\"\s*:\s*\")([^\"]{4})[^\"]*(\") 失配，密钥原文泄漏。
        // 预编译简单正则扫描全量 body 的开销远小于 log.info 格式化与控制台输出。
        String body = req.getBody();
        if (body != null) {
            body = API_KEY_PATTERN.matcher(body).replaceAll("$1$2***$3");
            body = CAMEL_API_KEY_PATTERN.matcher(body).replaceAll("$1$2***$3");
        }
        body = truncate(body);
        log.info("[HTTP] {} {} body={}", req.getMethod(), req.getUrl(), body);
    }

    private void logResponse(HttpResponse resp) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("[HTTP] {} body={}", resp.getStatusCode(), truncate(resp.getBody()));
    }

    /** 超过 {@link #MAX_BODY_LOG_LEN} 的报文体截断，避免超长字符串拖慢请求关键路径 */
    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_LOG_LEN) {
            return body;
        }
        return body.substring(0, MAX_BODY_LOG_LEN) + "...(truncated, total " + body.length() + " chars)";
    }
}
