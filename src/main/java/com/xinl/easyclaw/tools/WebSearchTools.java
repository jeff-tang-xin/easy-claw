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

@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private final HttpClient httpClient;

    public WebSearchTools() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Tool(name = "web_search", description = "搜索网络获取信息，返回搜索摘要和相关内容")
    public String webSearch(@ToolParam(name = "query", description = "搜索关键词") String query) {
        log.info("网络搜索: {}", query);
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://www.googleapis.com/customsearch/v1?q=" + encodedQuery;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return "搜索结果: " + response.body().substring(0, Math.min(2000, response.body().length()));
            } else {
                return "⚠️ 搜索请求返回状态码: " + response.statusCode() + "。请配置有效的 API Key。";
            }
        } catch (Exception e) {
            log.error("网络搜索失败: {}", e.getMessage());
            return "⚠️ 网络搜索暂时不可用: " + e.getMessage() + "\n\n你可以直接根据已有知识回答用户的问题。";
        }
    }

    @Tool(name = "fetch_url", description = "获取指定 URL 的网页内容，返回网页的文本内容")
    public String fetchUrl(@ToolParam(name = "url", description = "要获取的网页 URL") String url) {
        log.info("获取网页: {}", url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                int end = Math.min(3000, body.length());
                return body.substring(0, end) + (body.length() > 3000 ? "\n... (内容已截断)" : "");
            } else {
                return "⚠️ 请求失败，状态码: " + response.statusCode();
            }
        } catch (Exception e) {
            log.error("获取网页失败: {}", e.getMessage());
            return "⚠️ 获取网页失败: " + e.getMessage();
        }
    }
}