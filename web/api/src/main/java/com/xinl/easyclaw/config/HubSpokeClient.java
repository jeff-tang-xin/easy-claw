package com.xinl.easyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * hub spoke 端点 HTTP 客户端：知识库/黑板 cloud 存储分流共用。
 * <p>
 * 仿照 {@link CloudBootstrapService} 的 {@link java.net.http.HttpClient}（2s 连接 / 5s 读超时），
 * hubUrl/appKey 每次调用时从 {@link CloudProperties} 现取（设置热重载后无需重启）。
 * <p>
 * 与 bootstrap 的「失败不阻塞」语义不同：本客户端任何失败（未配置/网络/非 2xx）一律抛
 * {@link HubCallException}，由调用方决定报错方式 —— 存储分流场景<b>严禁静默降级写本地</b>
 * （否则 cloud/local 两套数据分叉，且用户误以为写进了云端）。
 * <p>
 * 线程安全：HttpClient 不可变可并发复用，本类无共享可变状态。
 */
@Service
public class HubSpokeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudProperties cloudProperties;
    private final HttpClient http;

    public HubSpokeClient(CloudProperties cloudProperties) {
        this.cloudProperties = cloudProperties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** hub 调用失败（未配置/网络/非 2xx/响应解析失败）；statusCode 非 2xx 时非空 */
    public static class HubCallException extends RuntimeException {
        private final Integer statusCode;

        public HubCallException(String message) {
            super(message);
            this.statusCode = null;
        }

        public HubCallException(String message, Integer statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public HubCallException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = null;
        }

        public Integer statusCode() {
            return statusCode;
        }
    }

    /** GET hub spoke 端点，返回 2xx 响应体；非 2xx / 网络失败抛 {@link HubCallException} */
    public String get(String pathWithQuery) {
        return call("GET", pathWithQuery, null);
    }

    /** POST JSON body 到 hub spoke 端点，返回 2xx 响应体；非 2xx / 网络失败抛 {@link HubCallException} */
    public String post(String path, Map<String, Object> payload) {
        try {
            return call("POST", path, MAPPER.writeValueAsString(payload));
        } catch (IOException e) {
            throw new HubCallException("hub 请求序列化失败：" + e.getMessage(), e);
        }
    }

    /** DELETE hub spoke 端点，返回 2xx 响应体；非 2xx / 网络失败抛 {@link HubCallException} */
    public void delete(String pathWithQuery) {
        call("DELETE", pathWithQuery, null);
    }

    private String call(String method, String path, String jsonBody) {
        String hubUrl = cloudProperties.getHubUrl();
        String appKey = cloudProperties.getAppKey();
        if (hubUrl == null || hubUrl.isBlank() || appKey == null || appKey.isBlank()) {
            throw new HubCallException("cloud 未配置（hub-url/app-key 缺失），无法访问 hub 存储");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(hubUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + appKey.trim())
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HubCallException("hub 返回 " + response.statusCode() + "：" + path,
                        response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new HubCallException("hub 不可达：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubCallException("hub 调用被中断", e);
        }
    }

    /** 查询参数编码：空格用 %20（不用 +），避免服务端按表单语义解码产生歧义 */
    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 便捷构造查询串（保持插入序，便于测试断言） */
    public static String query(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(params).entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(enc(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }
}
