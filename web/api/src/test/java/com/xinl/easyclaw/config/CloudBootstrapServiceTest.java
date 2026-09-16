package com.xinl.easyclaw.config;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.ModelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cloud 供给方：bootstrap 快照拉取（成功/401/不可达/本地模式/缺 hub-url）+
 * providers 表 cloud 条目注册（复刻 application-cloud.yml 的供给形态：
 * 条目是 yml 静态供给，注册与 bootstrap 快照解耦）。
 * hub 用内嵌 JDK HttpServer 桩，响应内容 ASCII。
 */
class CloudBootstrapServiceTest {

    private static final String BOOTSTRAP_JSON = """
            {"appKey":{"id":1,"name":"k","keyPrefix":"eck-abcdefgh","orgId":7},
             "org":{"id":7,"name":"Acme","slug":"acme"},
             "providers":[{"slug":"openai-pool","apiType":"openai","models":["gpt-4o","gpt-4o-mini"]},
                          {"slug":"claude-pool","apiType":"anthropic","models":["claude-a","gpt-4o"]}],
             "permissions":["llm.invoke"]}""";

    private HttpServer hub;
    private final AtomicInteger bootstrapCalls = new AtomicInteger();
    private final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startHub() throws IOException {
        hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hub.createContext("/api/spoke/bootstrap", ex -> {
            bootstrapCalls.incrementAndGet();
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            int status = stubStatus.get();
            String body = status == 200 ? BOOTSTRAP_JSON : "{\"code\":\"AUTH_INVALID\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        hub.start();
    }

    @AfterEach
    void cleanup() {
        if (hub != null) {
            hub.stop(0);
        }
        ModelRegistry.reset();
    }

    // ---------- bootstrap 快照 ----------

    @Test
    void refreshParsesSnapshot() {
        CloudBootstrapService service = new CloudBootstrapService(cloudProps());
        service.refresh();

        assertTrue(service.isAvailable());
        assertNull(service.status().lastError());
        assertEquals("Acme", service.status().orgName());
        assertEquals("acme", service.status().orgSlug());
        // 跨 provider 扁平去重，保持出现顺序
        assertEquals(List.of("gpt-4o", "gpt-4o-mini", "claude-a"), service.availableModels());
        assertEquals(List.of("llm.invoke"), service.status().permissions());
        assertEquals("Bearer eck-test-key", lastAuth.get());
        // 状态视图只回显 key 前缀，绝不回显完整密钥
        assertEquals("eck-test-key".substring(0, 12), service.status().appKeyPrefix());
    }

    @Test
    void refreshLocalModeDoesNotTouchNetwork() {
        CloudProperties cloud = cloudProps();
        cloud.setAppKey(null);
        CloudBootstrapService service = new CloudBootstrapService(cloud);
        service.refresh();

        assertFalse(service.isAvailable());
        assertTrue(service.availableModels().isEmpty());
        // 本地模式（app-key 未配置）是正常态而非错误
        assertNull(service.status().lastError());
        assertFalse(service.status().configured());
        assertEquals(0, bootstrapCalls.get());
    }

    @Test
    void refreshBlankHubUrlDoesNotTouchNetwork() {
        CloudProperties cloud = cloudProps();
        cloud.setHubUrl("");
        CloudBootstrapService service = new CloudBootstrapService(cloud);
        service.refresh();

        assertFalse(service.isAvailable());
        assertNotNull(service.status().lastError());
        assertEquals(0, bootstrapCalls.get());
    }

    @Test
    void refreshUnauthorizedKeepsUnavailable() {
        stubStatus.set(401);
        CloudBootstrapService service = new CloudBootstrapService(cloudProps());
        service.refresh();

        assertFalse(service.isAvailable());
        assertTrue(service.status().lastError().contains("401"));
    }

    @Test
    void refreshUnreachableDoesNotThrow() throws IOException {
        int freePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            freePort = ss.getLocalPort();
        }
        CloudProperties cloud = cloudProps();
        cloud.setHubUrl("http://127.0.0.1:" + freePort);
        CloudBootstrapService service = new CloudBootstrapService(cloud);
        service.refresh();

        assertFalse(service.isAvailable());
        assertNotNull(service.status().lastError());
    }

    // ---------- providers 表条目注册（复刻 cloud profile 供给形态，与快照解耦） ----------
    // 注：条目名用 "hubcloud" 而非 openai——openai 前缀在 vendored ModelRegistry 有内置 SPI
    // provider 兜底，canResolve 恒 true 无法作为注册判据；条目名本身是任意 key，机制相同。

    @Test
    void reloadRegistersCloudProviderEntry() {
        AgentScopeProperties props = new AgentScopeProperties();
        AgentScopeProperties.ProviderConfig entry = cloudProviderEntry();
        entry.setModelName("ark-code-latest");
        props.getProviders().put("hubcloud", entry);
        ModelRegistryService registry = new ModelRegistryService(props);
        registry.reload();

        assertTrue(ModelRegistry.canResolve("hubcloud:ark-code-latest"));
        assertNotNull(ModelRegistry.resolve("hubcloud:ark-code-latest"));
        // 条目只写一个模型名：其余模型不预注册，与 local provider 行为一致
        assertFalse(ModelRegistry.canResolve("hubcloud:gpt-4o-mini"));
    }

    /** 面外模型走 resolveOrBuild 统一动态路径构建（与 local provider 同构）。 */
    @Test
    void resolveOrBuildCloudModelOnDemand() {
        AgentScopeProperties props = new AgentScopeProperties();
        props.getProviders().put("hubcloud", cloudProviderEntry());
        ModelRegistryService registry = new ModelRegistryService(props);
        registry.reload();

        assertFalse(ModelRegistry.canResolve("hubcloud:unlisted-model"));
        assertNotNull(registry.resolveOrBuild("hubcloud:unlisted-model"));
        assertTrue(ModelRegistry.canResolve("hubcloud:unlisted-model"));
    }

    /** 快照不可用不影响注册：条目是 yml 静态供给，bootstrap 只服务状态观测。 */
    @Test
    void reloadRegistersCloudEvenWhenSnapshotUnavailable() {
        stubStatus.set(401);
        CloudBootstrapService cloudService = new CloudBootstrapService(cloudProps());
        cloudService.refresh();
        AgentScopeProperties props = new AgentScopeProperties();
        AgentScopeProperties.ProviderConfig entry = cloudProviderEntry();
        entry.setModelName("ark-code-latest");
        props.getProviders().put("hubcloud", entry);
        ModelRegistryService registry = new ModelRegistryService(props);
        registry.reload();

        assertFalse(cloudService.isAvailable());
        assertTrue(ModelRegistry.canResolve("hubcloud:ark-code-latest"));
    }

    /** 本地模式 = providers 表无云端条目（cloud profile 未激活），对应前缀模型解析不到。 */
    @Test
    void reloadSkipsCloudWhenLocalMode() {
        AgentScopeProperties props = new AgentScopeProperties();
        CloudBootstrapService cloudService = new CloudBootstrapService(new CloudProperties());
        cloudService.refresh();
        ModelRegistryService registry = new ModelRegistryService(props);
        registry.reload();

        assertFalse(ModelRegistry.canResolve("hubcloud:ark-code-latest"));
        assertEquals(0, bootstrapCalls.get());
    }

    // ---------- 辅助 ----------

    private String hubBase() {
        return "http://127.0.0.1:" + hub.getAddress().getPort();
    }

    /** 顶层 cloud 段（CloudProperties），等价 application-cloud.yml 的 cloud: 段 */
    private CloudProperties cloudProps() {
        CloudProperties cloud = new CloudProperties();
        cloud.setHubUrl(hubBase());
        cloud.setAppKey("eck-test-key");
        return cloud;
    }

    /** providers 表条目，等价 application-cloud.yml 中由 ${cloud.*} 占位派生出的 openai 条目 */
    private AgentScopeProperties.ProviderConfig cloudProviderEntry() {
        AgentScopeProperties.ProviderConfig entry = new AgentScopeProperties.ProviderConfig();
        entry.setBaseUrl(hubBase() + "/api/gateway/v1");
        entry.setApiKey("eck-test-key");
        return entry;
    }
}
