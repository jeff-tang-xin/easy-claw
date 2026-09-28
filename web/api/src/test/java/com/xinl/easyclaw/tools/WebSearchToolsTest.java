package com.xinl.easyclaw.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现并锁定 web_search 的两个缺陷：
 * <ol>
 *   <li>成功响应直接返回 Google 原始 JSON 并在 4000 字符处硬截断 —— 界面上表现为
 *       {@code rs": 4000, "url":} 这类乱码正文，模型也难以利用</li>
 *   <li>凭据只读环境变量，没有配置文件入口 —— 用户在 ~/.easyClaw/application.yml
 *       里填了 key 也不生效，工具永远「未配置」</li>
 * </ol>
 */
class WebSearchToolsTest {

    private static final String SAMPLE = """
            {
              "kind": "customsearch#search",
              "queries": {"request": [{"totalResults": "12800", "count": 10}]},
              "searchInformation": {"formattedTotalResults": "12,800"},
              "items": [
                {
                  "title": "Spring Boot 3.4 Release Notes",
                  "link": "https://github.com/spring-projects/spring-boot/wiki/3.4",
                  "snippet": "Spring Boot 3.4 upgrades to Framework 6.2 and adds structured logging."
                },
                {
                  "title": "What's new in Spring Boot 3.4",
                  "link": "https://spring.io/blog/2024/11/21/spring-boot-3-4",
                  "snippet": "Highlights include RestClient improvements and Micrometer 1.14."
                }
              ]
            }
            """;

    // ---------- 缺陷 1：输出必须是解析后的可读摘要，而不是原始 JSON ----------

    @Test
    @DisplayName("成功响应应格式化为可读摘要，不含原始 JSON 结构噪声")
    void formatsResultsInsteadOfRawJson() {
        String out = WebSearchTools.formatResults(SAMPLE);

        // 关键信息保留
        assertTrue(out.contains("Spring Boot 3.4 Release Notes"), "标题丢失: " + out);
        assertTrue(out.contains("https://spring.io/blog/2024/11/21/spring-boot-3-4"),
                "链接丢失: " + out);
        assertTrue(out.contains("structured logging"), "摘要丢失: " + out);

        // JSON 结构噪声必须消失（这些是界面乱码的直接来源）
        assertFalse(out.contains("customsearch#search"), "泄漏 kind 字段: " + out);
        assertFalse(out.contains("\"snippet\""), "泄漏 JSON 键名: " + out);
        assertFalse(out.contains("formattedTotalResults"), "泄漏内部字段: " + out);
    }

    @Test
    @DisplayName("零结果应给出明确提示而非空 JSON")
    void emptyResultsGiveClearHint() {
        String out = WebSearchTools.formatResults("{\"items\": []}");
        assertTrue(out.contains("未找到"), "零结果未给出可读提示: " + out);
        assertFalse(out.contains("{"), "零结果仍返回 JSON: " + out);
    }

    @Test
    @DisplayName("响应不可解析时降级为提示，不得抛异常")
    void malformedResponseDegradesGracefully() {
        String out = WebSearchTools.formatResults("not a json at all");
        assertTrue(out.startsWith("⚠️"), "畸形响应未降级为告警: " + out);
    }

    // ---------- 缺陷 2：凭据解析必须支持配置值，环境变量兜底 ----------

    @Test
    @DisplayName("配置值优先于环境变量")
    void configuredValueWins() {
        assertEquals("cfg-key",
                WebSearchTools.resolveCredential("cfg-key", "ENV_ONLY_FOR_TEST"));
    }

    @Test
    @DisplayName("配置为空/未解析占位符时回退环境变量名，且不返回占位符本身")
    void blankOrPlaceholderConfigFallsBack() {
        // 占位符（yml 中 ${X:} 未被解析的情况）不能被当作有效 key
        assertEquals("", WebSearchTools.resolveCredential("${GOOGLE_API_KEY:}", "NO_SUCH_ENV_VAR_XYZ"));
        assertEquals("", WebSearchTools.resolveCredential("   ", "NO_SUCH_ENV_VAR_XYZ"));
        assertEquals("", WebSearchTools.resolveCredential(null, "NO_SUCH_ENV_VAR_XYZ"));
    }

    @Test
    @DisplayName("系统属性可作为环境变量的等价来源（.env 加载路径）")
    void systemPropertyIsHonored() {
        String name = "EASYCLAW_TEST_SEARCH_KEY";
        System.setProperty(name, "from-prop");
        try {
            assertEquals("from-prop", WebSearchTools.resolveCredential("", name));
        } finally {
            System.clearProperty(name);
        }
    }

    @Test
    @DisplayName("未配置时的提示必须明确指出配置文件位置，而不是只说环境变量")
    void unconfiguredHintMentionsConfigFile() {
        String hint = new WebSearchTools("", "").webSearch("任意查询");
        assertTrue(hint.contains("application.yml"),
                "未配置提示未指引配置文件: " + hint);
        assertTrue(hint.contains("web-search") || hint.contains("web_search"),
                "未配置提示未给出配置键: " + hint);
    }
}
