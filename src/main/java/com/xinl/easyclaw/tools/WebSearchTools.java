package com.xinl.easyclaw.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 网络搜索 + 网页抓取工具。
 * <ul>
 *   <li>{@code web_search}：Google Custom Search，API Key 从 GOOGLE_API_KEY / GOOGLE_SEARCH_CX 环境变量读取</li>
 *   <li>{@code fetch_webpage}：直接 HTTP GET 网页，返回纯文本</li>
 * </ul>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private final HttpClient httpClient;

    public WebSearchTools() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Tool(name = "web_search", description = "通过搜索引擎检索最新信息，返回前 5 条结果的摘要。\n"
            + "【何时用】需要实时数据（新闻、版本号、价格、API 文档、最新规范等）或训练数据可能过时的问题。\n"
            + "【不要用于】读取已知 URL 的页面内容（用 fetch_webpage）；代码搜索（用 grep_files）；本地文件查找（用 search_files）。\n"
            + "【参数】query：搜索关键词，用自然语言或关键词短语，越具体命中越准。\n"
            + "【注意】若返回未配置提示，说明环境变量缺失，应改用 fetch_webpage 或已有知识回答，不要重复调用。")
    public String webSearch(@ToolParam(name = "query", description = "搜索关键词，例如 \"Spring Boot 3.4 新特性\" 或 \"deepseek-chat API pricing\"") String query) {
        log.info("网络搜索: {}", query);
        try {
            String apiKey = System.getenv().getOrDefault("GOOGLE_API_KEY", "").trim();
            String cx = System.getenv().getOrDefault("GOOGLE_SEARCH_CX", "").trim();
            if (apiKey.isEmpty() || cx.isEmpty()) {
                return "⚠️ web_search 未配置（缺少 GOOGLE_API_KEY 或 GOOGLE_SEARCH_CX 环境变量）。"
                        + " 请改用 fetch_webpage 直接抓取已知 URL，或根据已有知识回答。";
            }
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://www.googleapis.com/customsearch/v1?q=" + encodedQuery
                    + "&key=" + apiKey + "&cx=" + cx + "&num=5";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body().substring(0, Math.min(4000, response.body().length()));
            } else {
                return "⚠️ web_search 返回状态码 " + response.statusCode() + "（可能 API Key 无效或配额耗尽）。";
            }
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