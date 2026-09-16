package com.xinl.easyclaw.hub.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 上游 LLM provider HTTP 客户端（java.net.http，零新依赖）。
 * 非流式与流式都走 {@link #postStream}：调用方自行决定读全量还是逐行消费，
 * 由调用方负责关闭返回的 {@link InputStream}（同时断开上游连接）。
 * 连接超时 15s；读流不设总超时（SSE 长连接依赖上游心跳与客户端断开传播）。
 */
@Component
public class UpstreamClient {

    /** 读流最大缓冲由调用方控制；连接超时固定。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public record UpstreamResponse(int status, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /**
     * POST JSON，返回状态码 + 响应体流（调用方负责关闭）。
     *
     * @throws GatewayException 连接失败/中断（502 upstream_unavailable）
     */
    public UpstreamResponse postStream(String url, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        try {
            HttpResponse<InputStream> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            return new UpstreamResponse(resp.statusCode(), resp.body());
        } catch (IOException e) {
            throw new GatewayException(502, "上游 provider 连接失败：" + e.getMessage(),
                    "server_error", "upstream_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(502, "上游 provider 调用被中断", "server_error", "upstream_unavailable");
        }
    }

    /** 便捷：读全量响应体（非流式路径）。 */
    public record UpstreamFull(int status, byte[] body) {
    }

    public UpstreamFull post(String url, Map<String, String> headers, byte[] body) {
        try (UpstreamResponse resp = postStream(url, headers, body)) {
            return new UpstreamFull(resp.status(), resp.body().readAllBytes());
        } catch (IOException e) {
            throw new GatewayException(502, "读取上游响应失败：" + e.getMessage(),
                    "server_error", "upstream_unavailable");
        }
    }
}
