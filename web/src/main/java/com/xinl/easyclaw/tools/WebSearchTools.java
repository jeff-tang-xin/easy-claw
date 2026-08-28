package com.xinl.easyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 网络搜索 + 网页抓取工具。
 * <ul>
 *   <li>{@code web_search}：Google Custom Search，凭据按「配置值 → 环境变量/系统属性」解析，
 *       结果解析为可读摘要（标题 / 链接 / 摘要）</li>
 *   <li>{@code fetch_webpage}：直接 HTTP GET 网页，返回纯文本</li>
 * </ul>
 * 配置入口（{@code ~/.easyClaw/application.yml}）：
 * <pre>
 * tools:
 *   web-search:
 *     api-key: &lt;Google API Key&gt;
 *     cx: &lt;Custom Search 引擎 ID&gt;
 * </pre>
 * 留空则回退环境变量 {@code GOOGLE_API_KEY} / {@code GOOGLE_SEARCH_CX}。
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单条结果摘要的最大长度，防止个别站点的超长 snippet 挤占上下文 */
    private static final int MAX_SNIPPET = 300;
    /** 返回给模型的结果条数上限（与请求的 num 对齐） */
    private static final int MAX_ITEMS = 5;

    private final HttpClient httpClient;
    private final String configuredApiKey;
    private final String configuredCx;

    public WebSearchTools(@Value("${tools.web-search.api-key:}") String apiKey,
                          @Value("${tools.web-search.cx:}") String cx) {
        this.configuredApiKey = apiKey;
        this.configuredCx = cx;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 解析凭据：配置值优先，其次系统属性（.env 加载路径），最后环境变量。
     * <p>
     * 未被 Spring 解析掉的占位符（形如 {@code ${GOOGLE_API_KEY:}}）视为「未配置」——
     * 否则会把占位符字符串当成真 key 发出去，换来一个难以理解的 400。
     *
     * @param configured 配置文件中的值，可为 null
     * @param envName    回退使用的环境变量名
     * @return 解析出的凭据；均缺失时返回空串（不返回 null，调用方只需判空）
     */
    static String resolveCredential(String configured, String envName) {
        if (configured != null && !configured.isBlank() && !configured.startsWith("${")) {
            return configured.trim();
        }
        String fromProp = System.getProperty(envName);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "";
    }

    /**
     * 把 Google Custom Search 响应解析为可读摘要。
     * <p>
     * 早期实现直接返回原始 JSON 并在 4000 字符处硬截断，导致界面出现
     * {@code rs": 4000, "url":} 这类残片，模型也要额外花 token 去解析 JSON。
     *
     * @param body 原始响应体
     * @return 编号列表形式的纯文本摘要；不可解析时返回告警提示
     */
    static String formatResults(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return "⚠️ web_search 无法解析搜索服务响应（响应格式异常）。请改用 fetch_webpage 或根据已有知识回答。";
        }
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "未找到相关结果。可尝试更换关键词，或改用 fetch_webpage 抓取已知 URL。";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(MAX_ITEMS, items.size());
        for (int i = 0; i < n; i++) {
            JsonNode it = items.get(i);
            String title = it.path("title").asText("").trim();
            String link = it.path("link").asText("").trim();
            String snippet = it.path("snippet").asText("")
                    .replaceAll("\\s+", " ").trim();
            if (snippet.length() > MAX_SNIPPET) {
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            }
            sb.append(i + 1).append(". ").append(title.isEmpty() ? "(无标题)" : title).append('\n');
            if (!link.isEmpty()) {
                sb.append("   ").append(link).append('\n');
            }
            if (!snippet.isEmpty()) {
                sb.append("   ").append(snippet).append('\n');
            }
            if (i < n - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    @Tool(name = "web_search", description = "通过搜索引擎检索最新信息，返回前 5 条结果的摘要。\n"
            + "【何时用】需要实时数据（新闻、版本号、价格、API 文档、最新规范等）或训练数据可能过时的问题。\n"
            + "【不要用于】读取已知 URL 的页面内容（用 fetch_webpage）；代码搜索（用 grep_files）；本地文件查找（用 search_files）。\n"
            + "【参数】query：搜索关键词，用自然语言或关键词短语，越具体命中越准。\n"
            + "【注意】若返回未配置提示，说明缺少搜索凭据，应改用 fetch_webpage 或已有知识回答，不要重复调用。")
    public String webSearch(
            @ToolParam(name = "query",
                    description = "搜索关键词，例如 \"Spring Boot 3.4 新特性\" 或 \"deepseek-chat API pricing\"")
            String query) {
        log.info("网络搜索: {}", query);
        try {
            String apiKey = resolveCredential(configuredApiKey, "GOOGLE_API_KEY");
            String cx = resolveCredential(configuredCx, "GOOGLE_SEARCH_CX");
            if (apiKey.isEmpty() || cx.isEmpty()) {
                return "⚠️ web_search 未配置搜索凭据。请在 ~/.easyClaw/application.yml 中填写"
                        + " tools.web-search.api-key 与 tools.web-search.cx"
                        + "（或设置 GOOGLE_API_KEY / GOOGLE_SEARCH_CX 环境变量），改动后重启生效。"
                        + " 当前请改用 fetch_webpage 直接抓取已知 URL，或根据已有知识回答。";
            }
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://www.googleapis.com/customsearch/v1?q=" + encodedQuery
                    + "&key=" + apiKey + "&cx=" + cx + "&num=" + MAX_ITEMS;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return formatResults(response.body());
            }
            // 凭据/配额类错误单独提示，避免模型盲目重试
            if (response.statusCode() == 403 || response.statusCode() == 429) {
                return "⚠️ web_search 被拒绝（HTTP " + response.statusCode()
                        + "）：API Key 无效、未启用 Custom Search API 或配额已耗尽。"
                        + " 请检查 tools.web-search 配置，本轮改用 fetch_webpage 或已有知识回答。";
            }
            return "⚠️ web_search 返回状态码 " + response.statusCode() + "。请改用 fetch_webpage 或根据已有知识回答。";
        } catch (Exception e) {
            log.error("网络搜索失败: {}", e.getMessage());
            return "⚠️ web_search 调用失败: " + e.getMessage()
                    + "。请改用 fetch_webpage 或根据已有知识回答。";
        }
    }

    @Tool(name = "fetch_webpage", description = "获取指定 URL 的网页文本内容（自动去除 HTML 标签）。"
            + " 适合读取文档、API 说明、博客文章等。")
    public String fetchWebpage(@ToolParam(name = "url", description = "要获取的完整 URL") String url) {
        log.info("获取网页: {}", url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Easy-Claw/1.0; +https://github.com/easy-claw)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String text = stripHtml(response.body());
                int end = Math.min(6000, text.length());
                return text.substring(0, end) + (text.length() > 6000 ? "\n... (内容已截断)" : "");
            } else {
                return "⚠️ fetch_webpage 返回状态码 " + response.statusCode() + "。";
            }
        } catch (Exception e) {
            log.error("获取网页失败: {}", e.getMessage());
            return "⚠️ fetch_webpage 调用失败: " + e.getMessage();
        }
    }

    /** 简单去除 HTML 标签，保留可见文本（不处理 script/style 等） */
    private static String stripHtml(String html) {
        if (html == null) return "";
        String text = html.replaceAll("(?is)<script.*?>.*?</script>", "")
                .replaceAll("(?is)<style.*?>.*?</style>", "")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return text;
    }
}