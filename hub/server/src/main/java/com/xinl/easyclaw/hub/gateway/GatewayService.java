package com.xinl.easyclaw.hub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.entity.GatewayLogEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.CryptoService;
import com.xinl.easyclaw.hub.service.ProviderService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * LLM 网关编排（设计 §4）：appkey 认证后的路由（model → 绑定 → provider）、双协议转发
 * （openai 透传 / anthropic 转换）、SSE 流式透传、全量详单异步落库。
 * 真实 provider key 仅在转发瞬间解密注入请求头，不下发、不落日志。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /**
     * 逻辑模型别名：spoke（web 云端模式）恒定发送 {@code hub_cloud}，hub 按 appkey 配置的
     * cloudProviderId/cloudModelName 解析到唯一真实 provider+model，再在转发前改写请求体 model。
     */
    public static final String HUB_CLOUD_MODEL = "hub_cloud";

    private final AppKeyProviderBindingRepository bindings;
    private final AppKeyRepository appKeys;
    private final LlmProviderRepository providers;
    private final CryptoService cryptoService;
    private final UpstreamClient upstream;
    private final AnthropicTranslator anthropicTranslator;
    private final GatewayLogWriter logWriter;
    private final ObjectMapper om;

    public GatewayService(AppKeyProviderBindingRepository bindings, AppKeyRepository appKeys,
                          LlmProviderRepository providers,
                          CryptoService cryptoService, UpstreamClient upstream,
                          AnthropicTranslator anthropicTranslator, GatewayLogWriter logWriter,
                          ObjectMapper om) {
        this.bindings = bindings;
        this.appKeys = appKeys;
        this.providers = providers;
        this.cryptoService = cryptoService;
        this.upstream = upstream;
        this.anthropicTranslator = anthropicTranslator;
        this.logWriter = logWriter;
        this.om = om;
    }

    /** 网关返回：JSON（非流式/错误）或 SSE 流。 */
    public sealed interface GatewayOutcome {
        record Json(int status, ObjectNode body) implements GatewayOutcome {
        }

        record Stream(StreamingResponseBody body) implements GatewayOutcome {
        }
    }

    // ==================== chat/completions ====================

    public GatewayOutcome chatCompletion(AppKeyContext ctx, byte[] rawBody, String clientIp) {
        long startNanos = System.nanoTime();
        GatewayLogEntity logEntity = newLog(ctx, clientIp, rawBody);
        try {
            JsonNode request = parseRequest(rawBody);
            String reqModel = request.path("model").asText("");
            if (reqModel.isBlank()) {
                throw new GatewayException(400, "缺少 model 字段", "invalid_request_error", "missing_model");
            }
            boolean stream = request.path("stream").asBoolean(false);
            logEntity.setStream(stream);
            // 先记请求模型名：路由失败时详单仍是客户端实际所发（与重构前行为一致）
            logEntity.setModel(reqModel);

            RoutePlan plan = route(ctx, reqModel);
            String effectiveModel = plan.effectiveModel();
            // hub_cloud 别名场景：成功路由的详单改记真实生效模型
            logEntity.setModel(effectiveModel);
            logEntity.setProviderId(plan.provider().getId());
            logEntity.setProviderSlug(plan.provider().getSlug());
            logEntity.setApiType(plan.provider().getApiType());
            String apiKey = cryptoService.decrypt(plan.provider().getApiKeyCiphertext());

            return "anthropic".equals(plan.provider().getApiType())
                    ? forwardAnthropic(logEntity, request, effectiveModel, stream, plan.provider(), apiKey,
                            startNanos)
                    : forwardOpenAi(logEntity, rawBody, plan, stream, plan.provider(), apiKey, startNanos);
        } catch (GatewayException e) {
            logEntity.setStatus("error");
            logEntity.setHttpStatus(e.status());
            logEntity.setErrorMessage(e.getMessage());
            logEntity.setLatencyMs(elapsedMs(startNanos));
            logWriter.write(logEntity);
            throw e;
        }
    }

    private JsonNode parseRequest(byte[] rawBody) {
        try {
            JsonNode node = om.readTree(rawBody);
            if (!node.isObject()) {
                throw new GatewayException(400, "请求体必须是 JSON 对象", "invalid_request_error", "invalid_json");
            }
            return node;
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(400, "请求体不是合法 JSON", "invalid_request_error", "invalid_json");
        }
    }

    /**
     * 路由规划：provider + 真实生效模型。
     * <ul>
     *   <li>逻辑别名 {@link #HUB_CLOUD_MODEL}：按 appkey 自身配置的 cloudProviderId/cloudModelName
     *       解析到唯一真实 provider+model（未配置 → 404）。</li>
     *   <li>其它模型名：走绑定面路由——精确绑定优先，其次“全部模型”绑定（要求模型在 provider 清单内）。</li>
     * </ul>
     * provider 必须 active；hub_cloud 另复核其 cloudModelName 仍在绑定面内（配置后绑定可能被改）。
     */
    private RoutePlan route(AppKeyContext ctx, String reqModel) {
        if (HUB_CLOUD_MODEL.equals(reqModel)) {
            return resolveCloudRoute(ctx);
        }
        LlmProviderEntity provider = resolveByBinding(ctx.appKeyId(), reqModel);
        return new RoutePlan(provider, reqModel, false);
    }

    /** hub_cloud 别名路由：取 appkey 配置，校验 active + 仍在绑定面内。 */
    private RoutePlan resolveCloudRoute(AppKeyContext ctx) {
        AppKeyEntity key = appKeys.findById(ctx.appKeyId()).orElseThrow(() ->
                new GatewayException(401, "appkey 不存在", "authentication_error", "invalid_api_key"));
        Long pid = key.getCloudProviderId();
        String realModel = key.getCloudModelName();
        if (pid == null || realModel == null || realModel.isBlank()) {
            throw new GatewayException(404, "该 appkey 未配置 hub_cloud 路由",
                    "invalid_request_error", "cloud_route_not_configured");
        }
        LlmProviderEntity provider = providers.findById(pid).orElseThrow(() ->
                new GatewayException(404, "hub_cloud 路由的 provider 已被删除",
                        "invalid_request_error", "cloud_route_not_configured"));
        if (!"active".equals(provider.getStatus())) {
            throw new GatewayException(404, "hub_cloud 路由的 provider 已禁用：" + provider.getSlug(),
                    "invalid_request_error", "cloud_route_not_configured");
        }
        if (!ProviderService.parseModels(provider.getModels()).contains(realModel)) {
            throw new GatewayException(404, "hub_cloud 路由的模型已不在 provider 可用清单：" + realModel,
                    "invalid_request_error", "cloud_route_not_configured");
        }
        if (!bindingCovers(ctx.appKeyId(), pid, realModel)) {
            throw new GatewayException(404, "hub_cloud 路由目标已不在 appkey 绑定模型面内",
                    "invalid_request_error", "cloud_route_not_configured");
        }
        return new RoutePlan(provider, realModel, true);
    }

    /** 绑定面路由：精确绑定（modelName=model）优先，其次全模型绑定（modelName='' 且模型在 provider 清单）。 */
    private LlmProviderEntity resolveByBinding(Long appKeyId, String model) {
        List<AppKeyProviderBindingEntity> bindingList = bindings.findByAppKeyId(appKeyId);
        if (bindingList.isEmpty()) {
            throw new GatewayException(403, "appkey 未绑定任何 provider", "invalid_request_error", "no_binding");
        }
        Map<Long, LlmProviderEntity> providerMap = providers.findAllById(
                        bindingList.stream().map(AppKeyProviderBindingEntity::getProviderId).toList())
                .stream().collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        AppKeyProviderBindingEntity fallback = null;
        for (AppKeyProviderBindingEntity b : bindingList) {
            LlmProviderEntity p = providerMap.get(b.getProviderId());
            if (p == null || !"active".equals(p.getStatus())) {
                continue;
            }
            if (b.getModelName() != null && b.getModelName().equals(model)) {
                return p;
            }
            if ((b.getModelName() == null || b.getModelName().isEmpty()) && fallback == null
                    && ProviderService.parseModels(p.getModels()).contains(model)) {
                fallback = b;
            }
        }
        if (fallback != null) {
            return providerMap.get(fallback.getProviderId());
        }
        throw new GatewayException(404, "模型不可用或未绑定：" + model, "invalid_request_error", "model_not_found");
    }

    /** 判定指定 provider+model 是否仍在 appkey 绑定面内（精确绑定，或“全部模型”绑定）。 */
    private boolean bindingCovers(Long appKeyId, Long providerId, String model) {
        return bindings.findByAppKeyId(appKeyId).stream()
                .filter(b -> providerId.equals(b.getProviderId()))
                .anyMatch(b -> model.equals(b.getModelName())
                        || (b.getModelName() == null || b.getModelName().isEmpty()));
    }

    /** 路由结果：目标 provider + 真实生效模型 + 是否来自 hub_cloud 别名（决定是否改写请求体 model）。 */
    private record RoutePlan(LlmProviderEntity provider, String effectiveModel, boolean cloudAlias) {
    }

    // ==================== openai 兼容透传 ====================

    private GatewayOutcome forwardOpenAi(GatewayLogEntity logEntity, byte[] rawBody, RoutePlan plan,
                                         boolean stream, LlmProviderEntity provider, String apiKey,
                                         long startNanos) {
        String url = joinUrl(provider.getBaseUrl(), "/chat/completions");
        Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
        // hub_cloud 别名：上游看到的必须是真实模型名，转发前重写字节体里的 model。
        byte[] outBody = plan.cloudAlias() ? rewriteOpenAiModel(rawBody, plan.effectiveModel()) : rawBody;
        if (!stream) {
            UpstreamClient.UpstreamFull resp = upstream.post(url, headers, outBody);
            boolean ok = resp.status() >= 200 && resp.status() < 300;
            String bodyStr = new String(resp.body(), StandardCharsets.UTF_8);
            fillLog(logEntity, resp.status(), ok, bodyStr, ok ? null : "上游返回 " + resp.status(), startNanos);
            logWriter.write(logEntity);
            return jsonOutcome(resp.status(), bodyStr);
        }
        UpstreamClient.UpstreamResponse resp = upstream.postStream(url, headers, outBody);
        if (resp.status() < 200 || resp.status() >= 300) {
            String errBody = readAllQuietly(resp);
            fillLog(logEntity, resp.status(), false, errBody, "上游返回 " + resp.status(), startNanos);
            logWriter.write(logEntity);
            return jsonOutcome(resp.status(), errBody);
        }
        return new GatewayOutcome.Stream(sseBody(logEntity, resp, null, startNanos));
    }

    /** 把 OpenAI 形态请求体里的 model 改写为真实模型名（仅 hub_cloud 别名场景），解析失败返回 400。 */
    private byte[] rewriteOpenAiModel(byte[] rawBody, String effectiveModel) {
        try {
            JsonNode node = om.readTree(rawBody);
            if (!node.isObject()) {
                throw new GatewayException(400, "请求体必须是 JSON 对象", "invalid_request_error", "invalid_json");
            }
            ((ObjectNode) node).put("model", effectiveModel);
            return om.writeValueAsBytes(node);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(400, "请求体不是合法 JSON", "invalid_request_error", "invalid_json");
        }
    }

    // ==================== anthropic 转换转发 ====================

    private GatewayOutcome forwardAnthropic(GatewayLogEntity logEntity, JsonNode request, String model,
                                            boolean stream, LlmProviderEntity provider, String apiKey,
                                            long startNanos) {
        String base = stripTrailingSlash(provider.getBaseUrl());
        String url = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        byte[] body;
        try {
            // hub_cloud 别名场景下 request.model 仍是逻辑别名；Anthropic 上游必须收到真实模型名。
            ((ObjectNode) request).put("model", model);
            body = om.writeValueAsBytes(anthropicTranslator.toAnthropicRequest(request));
        } catch (Exception e) {
            throw new GatewayException(400, "请求转换失败：" + e.getMessage(), "invalid_request_error",
                    "invalid_request");
        }
        Map<String, String> headers = Map.of(
                "x-api-key", apiKey,
                "anthropic-version", AnthropicTranslator.ANTHROPIC_VERSION);
        if (!stream) {
            UpstreamClient.UpstreamFull resp = upstream.post(url, headers, body);
            String respStr = new String(resp.body(), StandardCharsets.UTF_8);
            boolean ok = resp.status() >= 200 && resp.status() < 300;
            if (ok) {
                ObjectNode converted = convertAnthropicResponse(respStr, model);
                fillLog(logEntity, resp.status(), true, converted.toString(), null, startNanos);
                logWriter.write(logEntity);
                return new GatewayOutcome.Json(200, converted);
            }
            ObjectNode wrapped = wrapUpstreamError(resp.status(), respStr);
            fillLog(logEntity, resp.status(), false, respStr, "上游返回 " + resp.status(), startNanos);
            logWriter.write(logEntity);
            return new GatewayOutcome.Json(resp.status(), wrapped);
        }
        UpstreamClient.UpstreamResponse resp = upstream.postStream(url, headers, body);
        if (resp.status() < 200 || resp.status() >= 300) {
            String errBody = readAllQuietly(resp);
            ObjectNode wrapped = wrapUpstreamError(resp.status(), errBody);
            fillLog(logEntity, resp.status(), false, errBody, "上游返回 " + resp.status(), startNanos);
            logWriter.write(logEntity);
            return new GatewayOutcome.Json(resp.status(), wrapped);
        }
        return new GatewayOutcome.Stream(sseBody(logEntity, resp,
                new AnthropicStreamTranslator(om, model), startNanos));
    }

    private ObjectNode convertAnthropicResponse(String respStr, String model) {
        try {
            return anthropicTranslator.toOpenAiResponse(om.readTree(respStr), model);
        } catch (Exception e) {
            throw new GatewayException(502, "上游响应解析失败", "server_error", "upstream_bad_response");
        }
    }

    /** 上游非 2xx 的 anthropic 错误体包装为 OpenAI 错误格式（openai 上游错误本身即该格式，直接透传）。 */
    private ObjectNode wrapUpstreamError(int status, String upstreamBody) {
        String message = "上游 provider 错误（HTTP " + status + "）";
        String type = status >= 500 ? "server_error" : "invalid_request_error";
        try {
            JsonNode node = om.readTree(upstreamBody);
            JsonNode error = node.path("error");
            if (error.hasNonNull("message")) {
                message = error.path("message").asText();
            }
        } catch (Exception ignore) {
            // 非 JSON 错误体：用默认 message
        }
        return GatewayErrors.openAiError(message, type, "upstream_error");
    }

    // ==================== SSE 流式体 ====================

    /**
     * 流式响应体：逐事件读取上游 SSE，透传（translator=null）或转换为 OpenAI chunk 后写给客户端，
     * 同步喂聚合器留档；结束后异步写详单。客户端断开/上游中断均收尾并记 error 详单。
     */
    private StreamingResponseBody sseBody(GatewayLogEntity logEntity, UpstreamClient.UpstreamResponse resp,
                                          AnthropicStreamTranslator translator, long startNanos) {
        return (OutputStream out) -> {
            OpenAiChunkAggregator aggregator = new OpenAiChunkAggregator(om);
            String failure = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            failure = writeSseEvent(out, event, data.toString(), translator, aggregator);
                            if (failure != null) {
                                break;
                            }
                        }
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).trim());
                    }
                    // 其它行（":" 心跳注释等）忽略，不转发
                }
                if (failure == null) {
                    writeRaw(out, "data: [DONE]\n\n");
                }
            } catch (IOException e) {
                failure = "流中断：" + e.getMessage();
            } finally {
                try {
                    resp.close();
                } catch (IOException ignore) {
                    // 已尽力
                }
            }
            boolean ok = failure == null;
            fillLog(logEntity, ok ? 200 : null, ok, aggregator.aggregate().toString(), failure, startNanos);
            logWriter.write(logEntity);
        };
    }

    /** 处理一个完整 SSE 事件；返回 null 表示正常，非 null 为失败原因（客户端断开）。 */
    private String writeSseEvent(OutputStream out, String event, String data,
                                 AnthropicStreamTranslator translator,
                                 OpenAiChunkAggregator aggregator) {
        try {
            if (translator == null) {
                aggregator.onChunk(data);
                writeRaw(out, "data: " + data + "\n\n");
                return null;
            }
            JsonNode dataNode;
            try {
                dataNode = om.readTree(data);
            } catch (Exception e) {
                return null; // 无法解析的事件静默跳过，不阻断流
            }
            for (String chunk : translator.onEvent(event, dataNode)) {
                aggregator.onChunk(chunk);
                writeRaw(out, "data: " + chunk + "\n\n");
            }
            return null;
        } catch (IOException e) {
            return "客户端连接断开";
        }
    }

    private static void writeRaw(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ==================== models ====================

    /** GET /models：该 appkey 绑定可见的模型清单（OpenAI models 格式；跨 provider 同名去重）。 */
    public ObjectNode listModels(AppKeyContext ctx) {
        List<AppKeyProviderBindingEntity> bindingList = bindings.findByAppKeyId(ctx.appKeyId());
        Map<Long, LlmProviderEntity> providerMap = providers.findAllById(
                        bindingList.stream().map(AppKeyProviderBindingEntity::getProviderId).toList())
                .stream().collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        Map<String, String> modelOwners = new TreeMap<>();
        for (AppKeyProviderBindingEntity b : bindingList) {
            LlmProviderEntity p = providerMap.get(b.getProviderId());
            if (p == null || !"active".equals(p.getStatus())) {
                continue;
            }
            if (b.getModelName() != null && !b.getModelName().isEmpty()) {
                modelOwners.putIfAbsent(b.getModelName(), p.getSlug());
            } else {
                for (String m : ProviderService.parseModels(p.getModels())) {
                    modelOwners.putIfAbsent(m, p.getSlug());
                }
            }
        }
        ObjectNode root = om.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        modelOwners.forEach((model, owner) -> {
            ObjectNode item = data.addObject();
            item.put("id", model);
            item.put("object", "model");
            item.put("created", 0);
            item.put("owned_by", owner);
        });
        return root;
    }

    // ==================== 日志与工具 ====================

    private GatewayOutcome jsonOutcome(int status, String bodyStr) {
        ObjectNode node;
        try {
            JsonNode parsed = om.readTree(bodyStr);
            node = parsed.isObject() ? (ObjectNode) parsed
                    : GatewayErrors.openAiError("上游响应非 JSON 对象", "server_error", "upstream_bad_response");
        } catch (Exception e) {
            node = GatewayErrors.openAiError("上游响应非 JSON", "server_error", "upstream_bad_response");
        }
        // 上游 2xx 但体非 JSON：状态与错误体不匹配，按 502 报
        boolean wrappedAsError = node.has("error");
        int finalStatus = (wrappedAsError && status >= 200 && status < 300) ? 502 : status;
        return new GatewayOutcome.Json(finalStatus, node);
    }

    private GatewayLogEntity newLog(AppKeyContext ctx, String clientIp, byte[] rawBody) {
        GatewayLogEntity e = new GatewayLogEntity();
        e.setAppKeyId(ctx.appKeyId());
        e.setOrgId(ctx.orgId());
        e.setUserId(ctx.userId());
        e.setKeyPrefix(ctx.keyPrefix());
        e.setClientIp(clientIp);
        e.setRequestBody(GatewayLogWriter.truncate(new String(rawBody, StandardCharsets.UTF_8)));
        return e;
    }

    private void fillLog(GatewayLogEntity e, Integer httpStatus, boolean ok, String responseBody,
                         String errorMessage, long startNanos) {
        e.setHttpStatus(httpStatus);
        e.setStatus(ok ? "success" : "error");
        e.setResponseBody(GatewayLogWriter.truncate(responseBody));
        e.setErrorMessage(errorMessage);
        e.setLatencyMs(elapsedMs(startNanos));
        fillUsage(e, responseBody);
    }

    /** 从（聚合后的）OpenAI 响应体提取 usage 计量。 */
    private void fillUsage(GatewayLogEntity e, String responseBody) {
        try {
            JsonNode usage = om.readTree(responseBody).path("usage");
            if (usage.path("prompt_tokens").isNumber()) {
                e.setPromptTokens(usage.path("prompt_tokens").asInt());
            }
            if (usage.path("completion_tokens").isNumber()) {
                e.setCompletionTokens(usage.path("completion_tokens").asInt());
            }
        } catch (Exception ignore) {
            // 无 usage（错误响应/截断）：留空
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String joinUrl(String baseUrl, String path) {
        return stripTrailingSlash(baseUrl) + path;
    }

    private static String stripTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String readAllQuietly(UpstreamClient.UpstreamResponse resp) {
        try (resp) {
            return new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[gateway] 读取上游错误响应失败：{}", e.getMessage());
            return "";
        }
    }
}
