package com.xinl.easyclaw.tools;

import com.embabel.agent.api.annotation.LlmTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private final HttpClient httpClient;

    public WebSearchTools() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @LlmTool(description = "使用 Google Custom Search API 搜索网络信息。返回前 5 条搜索结果的 JSON 片段。需要配置 GOOGLE_API_KEY 和 GOOGLE_SEARCH_CX 环境变量。")
    public String webSearch(
            @LlmTool.Param(description = "搜索关键词，支持自然语言查询")
            String query) {
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

    @LlmTool(description = "抓取指定 URL 的网页内容并提取纯文本。返回前 6000 字符的文本内容，HTML 标签已清除。")
    public String fetchWebpage(
            @LlmTool.Param(description = "要抓取的网页 URL，必须以 http:// 或 https:// 开头")
            String url) {
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
