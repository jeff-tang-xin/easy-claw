package com.xinl.easyclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 云端（hub）bootstrap 客户端：spoke 持 appkey 调 {@code GET /api/spoke/bootstrap}
 * 拉取配置快照（组织、按 appkey 绑定展开的模型面、服务权限）并缓存。
 * <p>
 * 定位：cloud 只是模型配置值（base-url/api-key/model-name 三要素）的一个【供给方】——
 * 模型注册/解析与本地 provider 走同一条公共路径（providers 表，见 application-cloud.yml，
 * 条目以 {@code ${cloud.hub-url}} / {@code ${cloud.app-key}} 占位引用顶层 cloud 段）。
 * 本类不参与模型注册，只做 cloud 的特例机制：快照供 cloud-status 观测，
 * 后续远程知识库/黑板（B2/B3）的权限裁决同样以本快照为准。
 * <p>
 * <b>失败策略：不阻塞、不静默降级。</b>hub 不可达 / appkey 无效时快照置为不可用并记录原因，
 * 启动与设置热重载照常完成；cloud provider 条目仍在 providers 表中（配置驱动，不依赖快照），
 * LLM 请求真实打到 hub 并以其响应为准——用户感知是请求报错，而不是被悄悄路由到本地端点。
 * <p>
 * 线程安全：快照整体替换（volatile 引用），读侧无锁。
 */
@Service
public class CloudBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(CloudBootstrapService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 连接超时：hub 本机/局域网部署，连不上应快速失败，不拖启动 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 读超时：bootstrap 是轻量 JSON，5s 足够 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final CloudProperties cloud;
    private final HttpClient httpClient;

    /** 最近一次成功快照；null 表示尚未成功过 */
    private volatile CloudSnapshot snapshot;
    /** 最近一次 refresh 的失败原因；成功时清空。与快照分离：失败后保留旧快照继续可用 */
    private volatile String lastError;
    private volatile Instant lastAttemptAt;

    public CloudBootstrapService(CloudProperties cloud) {
        this.cloud = cloud;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 重新拉取快照。本地模式（app-key 未配置）时清空快照直接返回（不触网）。
     * 启动与设置热重载时各调一次；失败仅记录，不抛异常。
     */
    public synchronized void refresh() {
        String appKey = cloud.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            // 本地模式：app-key 未配置即不接入云端，与无 cloud 配置行为一致
            snapshot = null;
            lastError = null;
            return;
        }
        String hubUrl = trimTailSlash(cloud.getHubUrl());
        if (hubUrl == null || hubUrl.isBlank()) {
            snapshot = null;
            lastError = "app-key 已配置但 hub-url 为空";
            log.warn("cloud bootstrap 跳过：{}", lastError);
            return;
        }
        lastAttemptAt = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(hubUrl + "/api/spoke/bootstrap"))
                    .header("Authorization", "Bearer " + appKey.trim())
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 不打印响应体：401 时 body 无敏感信息但 5xx 可能带内部细节，统一只记状态码
                lastError = "hub 返回 HTTP " + response.statusCode()
                        + (response.statusCode() == 401 || response.statusCode() == 403
                           ? "（appkey 无效/已吊销/权限不足）" : "");
                log.warn("cloud bootstrap 失败：{}", lastError);
                return;
            }
            snapshot = parse(response.body());
            lastError = null;
            log.info("cloud bootstrap 成功：org={}，可用模型 {} 个，权限 {} 个",
                    snapshot.orgName(), snapshot.models().size(), snapshot.permissions().size());
        } catch (Exception e) {
            lastError = "hub 不可达：" + e.getClass().getSimpleName() + " " + e.getMessage();
            log.warn("cloud bootstrap 失败：{}", lastError);
        }
    }

    /** 当前是否可用（启用且已有成功快照） */
    public boolean isAvailable() {
        return snapshot != null;
    }

    /** 可用模型清单（扁平去重，按 provider 顺序）；不可用时返回空表 */
    public List<String> availableModels() {
        CloudSnapshot s = snapshot;
        return s == null ? List.of() : s.models();
    }

    /**
     * 状态视图（设置页展示用）：configured（app-key 已配置）/ available / org / models / permissions / lastError / lastAttemptAt。
     */
    public CloudStatus status() {
        CloudSnapshot s = snapshot;
        String appKey = cloud.getAppKey();
        boolean configured = appKey != null && !appKey.isBlank();
        return new CloudStatus(
                configured,
                s != null,
                configured ? mask(appKey) : null,
                s == null ? null : s.orgName(),
                s == null ? null : s.orgSlug(),
                s == null ? List.of() : s.models(),
                s == null ? List.of() : s.permissions(),
                lastError,
                lastAttemptAt);
    }

    /** 配置键面变化（hub-url/app-key）由 SettingsService 合并后调用，等价 refresh */
    public void onConfigReloaded() {
        refresh();
    }

    private static CloudSnapshot parse(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        String orgName = root.path("org").path("name").asText("");
        String orgSlug = root.path("org").path("slug").asText("");
        Set<String> models = new LinkedHashSet<>();
        for (JsonNode p : root.path("providers")) {
            for (JsonNode m : p.path("models")) {
                if (m.isTextual() && !m.asText().isBlank()) {
                    models.add(m.asText().trim());
                }
            }
        }
        List<String> permissions = new ArrayList<>();
        for (JsonNode perm : root.path("permissions")) {
            if (perm.isTextual()) {
                permissions.add(perm.asText());
            }
        }
        return new CloudSnapshot(orgName, orgSlug, List.copyOf(models), List.copyOf(permissions), Instant.now());
    }

    private static String trimTailSlash(String url) {
        if (url == null) {
            return null;
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** appkey 只露前缀（hub 侧 keyPrefix 约定为前 12 字符），绝不在状态接口回显完整密钥 */
    private static String mask(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return null;
        }
        String t = appKey.trim();
        return t.length() <= 12 ? t : t.substring(0, 12);
    }

    /** bootstrap 快照（不可变；整体替换发布） */
    public record CloudSnapshot(String orgName, String orgSlug, List<String> models,
                                List<String> permissions, Instant fetchedAt) {
    }

    /** 设置页状态视图 */
    public record CloudStatus(boolean configured, boolean available, String appKeyPrefix,
                              String orgName, String orgSlug, List<String> models,
                              List<String> permissions, String lastError, Instant lastAttemptAt) {
    }
}
