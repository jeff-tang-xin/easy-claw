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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * 应用启动照常完成；cloud provider 条目仍在 providers 表中（配置驱动，不依赖快照），
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
    /** 附件/图片开关的 flag key（hub feature_flags 种子同名）；快照缺该 flag 时按放行处理 */
    public static final String FLAG_ALLOW_ATTACHMENTS = "allow_attachments";

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
            // 目录扩展（spec §4.1）：flags 与 tools 各自独立 try/catch，失败不阻塞 bootstrap——
            // 对应维度按缺省放行（flags 空表 → attachmentsAllowed=true；disabledTools 空集）
            Map<String, Boolean> flags = fetchFlags(hubUrl, appKey.trim());
            Set<String> disabledTools = fetchDisabledTools(hubUrl, appKey.trim());
            // 配置下发（S5）：menus / ops-servers / shell-commands 三维度并行拉取，
            // 各自独立 try/catch，失败 → 该维度空值，不阻塞 bootstrap（与 fetchFlags 同模式）
            List<SpokeMenuNodeView> menus;
            List<SpokeOpsServerView> opsServers;
            List<SpokeShellCommandView> shellCommands;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<List<SpokeMenuNodeView>> menusFuture =
                        CompletableFuture.supplyAsync(() -> fetchMenus(hubUrl, appKey.trim()), executor);
                CompletableFuture<List<SpokeOpsServerView>> opsFuture =
                        CompletableFuture.supplyAsync(() -> fetchOpsServers(hubUrl, appKey.trim()), executor);
                CompletableFuture<List<SpokeShellCommandView>> shellFuture =
                        CompletableFuture.supplyAsync(() -> fetchShellCommands(hubUrl, appKey.trim()), executor);
                menus = menusFuture.join();
                opsServers = opsFuture.join();
                shellCommands = shellFuture.join();
            }
            snapshot = parse(response.body(), flags, disabledTools, menus, opsServers, shellCommands);
            lastError = null;
            log.info("cloud bootstrap 成功：org={}，可用模型 {} 个，权限 {} 个，flags {} 个，禁用工具 {} 个，"
                            + "菜单 {} 个，运维服务器 {} 个，shell 白名单 {} 条",
                    snapshot.orgName(), snapshot.models().size(), snapshot.permissions().size(),
                    snapshot.flags().size(), snapshot.disabledTools().size(),
                    snapshot.menus().size(), snapshot.opsServers().size(), snapshot.shellCommands().size());
        } catch (Exception e) {
            lastError = "hub 不可达：" + e.getClass().getSimpleName() + " " + e.getMessage();
            log.warn("cloud bootstrap 失败：{}", lastError);
        }
    }

    /** 当前是否可用（启用且已有成功快照） */
    public boolean isAvailable() {
        return snapshot != null;
    }

    /** 最近一次成功快照；null 表示尚未成功过（本地模式或从未拉取成功）。门面 {@link CloudFeatureGate} 读用 */
    public CloudSnapshot snapshot() {
        return snapshot;
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
                // 附件开关（spec §4.5）：本地模式/无快照恒放行；快照缺该 flag 亦放行
                s == null || s.flags().getOrDefault(FLAG_ALLOW_ATTACHMENTS, true),
                lastError,
                lastAttemptAt);
    }

    /**
     * 前端配置下发视图（S5 配置上收）：cloud 模式返回 hub 下发的菜单树/运维服务器/shell 白名单；
     * 本地模式（无快照）cloudMode=false 且三个数组恒为空，前端据此回退本地渲染。
     * <p>
     * 读取前先做按需刷新（{@link #refreshIfStale()}）：spoke 启动时 hub 未就绪（旧构建/网络抖动）
     * 导致快照失败或三维度为空后，前端轮询本端点即可驱动 spoke 自动跟上 hub 配置，无需重启。
     */
    public CloudConfigView cloudConfig() {
        refreshIfStale();
        CloudSnapshot s = snapshot;
        boolean available = s != null;
        // 密码不再下发浏览器：快照仅服务端持有（connect 按 serverKey 取用），下发视图 password 置 null，
        // 仅保留 hasPassword 布尔供前端预判「一键连接 vs 当次手输」。
        // 此前明文随 30s 轮询反复传输并常驻浏览器内存，是凭证最大的暴露面
        List<SpokeOpsServerView> servers = s == null ? List.of()
                : s.opsServers().stream()
                        .map(o -> new SpokeOpsServerView(o.serverKey(), o.name(), o.host(), o.port(),
                                o.username(), o.description(), o.projectId(), null, o.hasPassword(), o.osType()))
                        .toList();
        return new CloudConfigView(
                available,
                available,
                s == null ? List.of() : s.menus(),
                servers,
                s == null ? List.of() : s.shellCommands());
    }

    /**
     * 按 serverKey 查 hub 下发的运维服务器（connect 用）。
     * host/port/username/password 全部以快照为准——前端只传 serverKey，不采信其传参。
     */
    public java.util.Optional<SpokeOpsServerView> findOpsServer(String serverKey) {
        CloudSnapshot s = snapshot;
        if (s == null || serverKey == null || serverKey.isBlank()) {
            return java.util.Optional.empty();
        }
        return s.opsServers().stream()
                .filter(o -> serverKey.equals(o.serverKey()))
                .findFirst();
    }

    /**
     * 按需刷新（惰性自愈）：cloud 已配置且距上次尝试超过节流间隔（{@code cloud.refresh-interval-seconds}，
     * 默认 60s，0=每次读取都刷新）时同步 refresh。启动时 hub 不可达/配置未就绪的故障在 hub 恢复后
     * 自动消失——前端轮询 cloud-config（30s）驱动本方法，节流保证不会高频打 hub。
     * 本地模式 / hub-url 缺失直接跳过（无远端可刷）。
     */
    private void refreshIfStale() {
        String appKey = cloud.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            return;
        }
        if (cloud.getHubUrl() == null || cloud.getHubUrl().isBlank()) {
            return;
        }
        long intervalSeconds = cloud.getRefreshIntervalSeconds();
        Duration interval = intervalSeconds > 0 ? Duration.ofSeconds(intervalSeconds) : Duration.ZERO;
        Instant last = lastAttemptAt;
        if (last == null || Duration.between(last, Instant.now()).compareTo(interval) >= 0) {
            refresh();
        }
    }

    private static CloudSnapshot parse(String body, Map<String, Boolean> flags, Set<String> disabledTools,
                                       List<SpokeMenuNodeView> menus, List<SpokeOpsServerView> opsServers,
                                       List<SpokeShellCommandView> shellCommands)
            throws Exception {
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
        return new CloudSnapshot(orgName, orgSlug, List.copyOf(models), List.copyOf(permissions),
                flags, disabledTools, menus, opsServers, shellCommands, Instant.now());
    }

    /** 拉取生效态 feature flags（flagKey → enabled）；任何失败返回空表（缺省放行语义） */
    private Map<String, Boolean> fetchFlags(String hubUrl, String appKey) {
        try {
            String body = fetchJson(hubUrl, appKey, "/api/spoke/feature-flags");
            if (body == null) {
                return Map.of();
            }
            Map<String, Boolean> flags = new LinkedHashMap<>();
            for (JsonNode n : MAPPER.readTree(body)) {
                String key = n.path("flagKey").asText("");
                if (!key.isBlank()) {
                    flags.put(key, n.path("enabled").asBoolean(true));
                }
            }
            return Map.copyOf(flags);
        } catch (Exception e) {
            log.warn("cloud feature-flags 拉取失败（按缺省放行）：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 拉取生效态为否的工具 key 集合；任何失败返回空集（不额外禁用工具） */
    private Set<String> fetchDisabledTools(String hubUrl, String appKey) {
        try {
            String body = fetchJson(hubUrl, appKey, "/api/spoke/tools");
            if (body == null) {
                return Set.of();
            }
            Set<String> disabled = new LinkedHashSet<>();
            for (JsonNode n : MAPPER.readTree(body)) {
                String key = n.path("toolKey").asText("");
                if (!key.isBlank() && !n.path("enabled").asBoolean(true)) {
                    disabled.add(key);
                }
            }
            return Set.copyOf(disabled);
        } catch (Exception e) {
            log.warn("cloud tools 拉取失败（不额外禁用工具）：{}", e.getMessage());
            return Set.of();
        }
    }

    /** 拉取 hub 下发的菜单树（path 空 = 分组节点）；任何失败返回空表（前端回退本地渲染） */
    private List<SpokeMenuNodeView> fetchMenus(String hubUrl, String appKey) {
        try {
            String body = fetchJson(hubUrl, appKey, "/api/spoke/menus");
            if (body == null) {
                return List.of();
            }
            return parseMenus(MAPPER.readTree(body));
        } catch (Exception e) {
            log.warn("cloud menus 拉取失败（菜单置空）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 拉取 hub 下发的运维服务器清单；任何失败返回空表 */
    private List<SpokeOpsServerView> fetchOpsServers(String hubUrl, String appKey) {
        try {
            String body = fetchJson(hubUrl, appKey, "/api/spoke/ops-servers");
            if (body == null) {
                return List.of();
            }
            List<SpokeOpsServerView> out = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(body)) {
                out.add(new SpokeOpsServerView(
                        n.path("serverKey").asText(""),
                        n.path("name").asText(""),
                        n.path("host").asText(""),
                        n.path("port").asInt(22),
                        n.path("username").asText(""),
                        n.path("description").asText(""),
                        // V18：归属项目 id；旧 hub 未下发该字段时保持 null（不造 0 哨兵）
                        n.hasNonNull("projectId") ? n.path("projectId").asLong() : null,
                        // V19：hub 解密后随目录下发；未设置/旧 hub 下发时保持 null
                        n.hasNonNull("password") ? n.path("password").asText() : null,
                        // V24：是否配置了密码（供前端预判一键连接；明文本身不下发浏览器）
                        n.hasNonNull("password") && !n.path("password").asText().isBlank(),
                        // V24：操作系统类型；旧 hub 未下发该字段时保持 null（容错）
                        n.hasNonNull("osType") ? n.path("osType").asText() : null));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("cloud ops-servers 拉取失败（运维服务器置空）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 拉取 hub 下发的 shell 命令白名单（subcommands 空 = 整命令放行）；任何失败返回空表 */
    private List<SpokeShellCommandView> fetchShellCommands(String hubUrl, String appKey) {
        try {
            String body = fetchJson(hubUrl, appKey, "/api/spoke/shell-commands");
            if (body == null) {
                return List.of();
            }
            List<SpokeShellCommandView> out = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(body)) {
                out.add(new SpokeShellCommandView(
                        n.path("cmd").asText(""),
                        stringList(n.path("subcommands"))));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("cloud shell-commands 拉取失败（白名单置空）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 递归解析菜单树；字段缺失按空串/空表处理（分组节点 path 为空） */
    private static List<SpokeMenuNodeView> parseMenus(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<SpokeMenuNodeView> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(new SpokeMenuNodeView(
                    n.path("menuKey").asText(""),
                    n.path("label").asText(""),
                    n.path("icon").asText(""),
                    n.path("path").asText(""),
                    n.path("requiredPerm").asText(""),
                    stringList(n.path("visibleRoles")),
                    parseMenus(n.path("children"))));
        }
        return List.copyOf(out);
    }

    private static List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isTextual()) {
                out.add(n.asText());
            }
        }
        return List.copyOf(out);
    }

    /** 单个 spoke 目录端点 GET；非 200 或网络失败返回 null（由调用方按缺省语义处理） */
    private String fetchJson(String hubUrl, String appKey, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(hubUrl + path))
                    .header("Authorization", "Bearer " + appKey)
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("cloud {} 拉取失败：HTTP {}", path, response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("cloud {} 拉取失败：{} {}", path, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
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

    /**
     * bootstrap 快照（不可变；整体替换发布）。
     * flags=生效态功能开关；disabledTools=生效态为否的工具 key；
     * menus/opsServers/shellCommands=hub 下发的配置面（S5 配置下发，失败维度为空表）。
     */
    public record CloudSnapshot(String orgName, String orgSlug, List<String> models,
                                List<String> permissions, Map<String, Boolean> flags,
                                Set<String> disabledTools, List<SpokeMenuNodeView> menus,
                                List<SpokeOpsServerView> opsServers,
                                List<SpokeShellCommandView> shellCommands, Instant fetchedAt) {
    }

    /** cloud-config 端点响应（menu/opsServers/shellCommands 在本地模式下恒为空数组） */
    public record CloudConfigView(boolean cloudMode, boolean available,
                                  List<SpokeMenuNodeView> menu,
                                  List<SpokeOpsServerView> opsServers,
                                  List<SpokeShellCommandView> shellCommands) {
    }

    /** 设置页状态视图（attachmentsAllowed 供前端隐藏附件入口，spec §4.5） */
    public record CloudStatus(boolean configured, boolean available, String appKeyPrefix,
                              String orgName, String orgSlug, List<String> models,
                              List<String> permissions, boolean attachmentsAllowed,
                              String lastError, Instant lastAttemptAt) {
    }
}
