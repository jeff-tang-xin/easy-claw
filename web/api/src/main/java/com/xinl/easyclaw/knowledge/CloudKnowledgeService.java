package com.xinl.easyclaw.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.config.HubSpokeClient;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库 cloud 实现：读写全部委托 hub 的 {@code /api/spoke/knowledge/*} 端点，
 * spoke 本地不落任何知识文件（防止两套数据分叉）。
 * <p>
 * 与本地实现（{@link LocalKnowledgeService}）的语义对齐点：write 校验与截断文案一致、
 * topic 走同一 {@link LocalKnowledgeService#safeTopic} 归一化、read 不存在返回空串、
 * list 按最后修改倒序、search 为 AND 子串匹配且权重「条目名 &gt; 摘要 &gt; 正文」。
 * 差异点：hub 无搜索端点，search 在 spoke 侧拉清单后逐条取正文做客户端匹配
 * （知识库条目量级为几十条，N+1 可接受）；hub 列宽 topic 200 / summary 500，
 * 写入前按列宽截断（本地无此限制）。V23 起读写均按 projectId 定位（统一落 knowledge_items）。
 * <p>
 * <b>失败语义：</b>write 失败返回可读的失败说明（不打断 AI 工具）；
 * 其余方法在 hub 不可达 / 非 2xx（除 read/exists 的 404 = 条目不存在）时直接抛
 * {@link HubSpokeClient.HubCallException} 让调用方看到错误 ——
 * <b>绝不静默降级读本地文件</b>（否则 cloud 与本地两套数据会分叉）。
 * <p>
 * 装配：由 {@link RoutingKnowledgeService} 按 cloud 模式分流到本实现。
 */
@Service
public class CloudKnowledgeService implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(CloudKnowledgeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条正文上限（字符）：与 LocalKnowledgeService 一致，超出截断 */
    private static final int MAX_CONTENT_CHARS = 50_000;
    /** 搜索默认返回条数：limit ≤ 0 时启用 */
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    /** 搜索片段上限（字符）：超出截断 */
    private static final int SNIPPET_MAX_CHARS = 200;
    /** hub knowledge_items.topic 列宽：写入前截断，避免 DB 层报错（V23 统一落 knowledge_items） */
    private static final int MAX_TOPIC_CHARS = 200;
    /** hub knowledge_items.summary 列宽：写入前截断 */
    private static final int MAX_SUMMARY_CHARS = 500;

    private final HubSpokeClient hub;

    public CloudKnowledgeService(HubSpokeClient hub) {
        this.hub = hub;
    }

    @Override
    public String write(String topic, String summary, String content, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return "❌ topic 不能为空。";
        }
        if (summary == null || summary.isBlank()) {
            return "❌ summary 不能为空。";
        }
        if (content == null || content.isBlank()) {
            return "❌ content 不能为空。";
        }
        String safeTopic = hubTopic(topic);
        String safeSummary = truncateTo(summary, MAX_SUMMARY_CHARS);
        String body = truncate(content);
        // V23：projectId 必填（hub 端按 (projectId, topic) 落 knowledge_items）；
        // 工作区未绑定项目属异常态，拒绝写入且绝不降级写本地（防两套数据分叉）
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return "❌ 写入失败：工作区未绑定 hub 项目（projectId 缺失），请先完成项目绑定。";
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("workspaceId", workspace.getWorkspaceId());
            payload.put("projectId", projectId);
            payload.put("topic", safeTopic);
            payload.put("summary", safeSummary);
            payload.put("content", body);
            hub.post("/api/spoke/knowledge/entries", payload);
            return "✅ 知识已写入云端知识库 `" + safeTopic + "`"
                    + (body.length() < content.length() ? "；正文过长已截断" : "");
        } catch (HubSpokeClient.HubCallException e) {
            // 不吞异常：让调用方知道这条没写进 hub（也绝不降级写本地，防两套数据分叉）
            log.warn("写入云端知识库失败: {}, {}", safeTopic, e.getMessage());
            return "❌ 写入失败：" + e.getMessage();
        }
    }

    @Override
    public List<KnowledgeEntry> list(WorkspaceContext workspace) {
        // V23：hub 端按 projectId 查 knowledge_items（与 hub 平台「知识条目」同一份数据）；
        // 工作区未绑定项目（存量异常态）时无数据可查，返回空清单
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return List.of();
        }
        String resp = hub.get("/api/spoke/knowledge/entries?" + HubSpokeClient.query(Map.of(
                "projectId", projectId)));
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (JsonNode n : parseArray(resp, "hub 知识清单响应解析失败")) {
            entries.add(new KnowledgeEntry(
                    n.path("topic").asText(""),
                    n.path("summary").asText(""),
                    n.path("lastModified").asLong(0),
                    n.path("fileSize").asLong(0)));
        }
        // 与本地一致：按最后修改时间倒序
        entries.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return List.copyOf(entries);
    }

    @Override
    public String read(String topic, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return "";
        }
        String resp;
        try {
            resp = hub.get("/api/spoke/knowledge/entry?" + HubSpokeClient.query(Map.of(
                    "projectId", projectId,
                    "topic", hubTopic(topic))));
        } catch (HubSpokeClient.HubCallException e) {
            if (e.statusCode() != null && e.statusCode() == 404) {
                // 条目不存在：接口约定返回空字符串（与本地一致）
                return "";
            }
            throw e;
        }
        try {
            return MAPPER.readTree(resp).path("content").asText("");
        } catch (IOException e) {
            throw new HubSpokeClient.HubCallException("hub 知识条目响应解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String topic, WorkspaceContext workspace) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return false;
        }
        try {
            hub.get("/api/spoke/knowledge/entry?" + HubSpokeClient.query(Map.of(
                    "projectId", projectId,
                    "topic", hubTopic(topic))));
            return true;
        } catch (HubSpokeClient.HubCallException e) {
            if (e.statusCode() != null && e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 全文搜索：hub 无搜索端点，在 spoke 侧做客户端匹配 ——
     * 拉条目清单后逐条取正文，按与本地一致的规则匹配（query 拆词 AND、
     * 权重「条目名命中 3 &gt; 摘要命中 2 &gt; 仅正文命中 1」、片段取首个正文命中行上下文）。
     * 单条正文拉取失败只跳过该条，不让整体搜索失败（与本地单文件容错一致）。
     */
    @Override
    public List<KnowledgeSearchHit> search(String query, int limit, WorkspaceContext workspace) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;
        List<String> terms = Arrays.stream(query.trim().split("\\s+"))
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        List<SearchCandidate> candidates = new ArrayList<>();
        for (KnowledgeEntry entry : list(workspace)) {
            String content;
            try {
                content = read(entry.topic(), workspace);
            } catch (HubSpokeClient.HubCallException e) {
                // 单条拉取失败不应让整体搜索失败（与本地单文件容错一致）
                log.warn("搜索跳过无法读取的知识条目: {}, {}", entry.topic(), e.getMessage());
                continue;
            }
            SearchCandidate c = match(entry.topic(), entry.summary(), content, terms);
            if (c != null) {
                candidates.add(c);
            }
        }
        // List.sort 稳定：同权重保持清单序
        candidates.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
        return candidates.stream()
                .limit(effectiveLimit)
                .map(c -> new KnowledgeSearchHit(c.topic(), c.summary(), c.snippet()))
                .toList();
    }

    // =============== 内部方法 ===============

    /**
     * topic 归一化：与本地同一 {@link LocalKnowledgeService#safeTopic}（保证两模写入的
     * topic 空间一致），再按 hub 列宽截断。
     */
    private static String hubTopic(String topic) {
        String s = LocalKnowledgeService.safeTopic(topic);
        return truncateTo(s, MAX_TOPIC_CHARS);
    }

    private static String truncateTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String truncate(String content) {
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + "\n\n…[truncated]";
    }

    /**
     * 对单条知识做 AND 子串匹配，返回命中候选；任一词在条目名/摘要/正文中都不出现则返回 null。
     * 权重：条目名命中 = 3 &gt; 摘要命中 = 2 &gt; 仅正文命中 = 1。
     * cloud 正文无 YAML front matter，摘要直接来自 hub 元数据。
     */
    private SearchCandidate match(String topic, String summary, String content, List<String> terms) {
        String topicLower = topic.toLowerCase(Locale.ROOT);
        String summaryLower = summary == null ? "" : summary.toLowerCase(Locale.ROOT);
        boolean topicHit = false;
        boolean summaryHit = false;
        boolean[] termMatched = new boolean[terms.size()];
        for (int t = 0; t < terms.size(); t++) {
            if (topicLower.contains(terms.get(t))) {
                termMatched[t] = true;
                topicHit = true;
            }
            if (summaryLower.contains(terms.get(t))) {
                termMatched[t] = true;
                summaryHit = true;
            }
        }

        List<String> bodyLines = content == null || content.isEmpty()
                ? List.of()
                : Arrays.asList(content.split("\\R", -1));
        int anchor = -1;
        for (int i = 0; i < bodyLines.size(); i++) {
            String lower = bodyLines.get(i).toLowerCase(Locale.ROOT);
            boolean anyHit = false;
            for (int t = 0; t < terms.size(); t++) {
                if (lower.contains(terms.get(t))) {
                    termMatched[t] = true;
                    anyHit = true;
                }
            }
            if (anyHit && anchor < 0) {
                anchor = i;
            }
        }

        for (boolean matched : termMatched) {
            if (!matched) {
                return null; // AND 语义：任一词不命中即整条不命中
            }
        }

        int weight = topicHit ? 3 : (summaryHit ? 2 : 1);
        String snippet = anchor >= 0 ? buildSnippet(bodyLines, anchor) : summary;
        return new SearchCandidate(topic, summary, snippet, weight);
    }

    /** 命中片段：首个正文命中行及其上下各 1 行（跳过空行），约 200 字符截断（与本地一致） */
    private String buildSnippet(List<String> bodyLines, int anchor) {
        int from = Math.max(0, anchor - 1);
        int to = Math.min(bodyLines.size(), anchor + 2); // exclusive
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            String line = bodyLines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ⏎ ");
            }
            sb.append(line);
        }
        String s = sb.toString();
        return s.length() <= SNIPPET_MAX_CHARS ? s : s.substring(0, SNIPPET_MAX_CHARS) + "…";
    }

    private List<JsonNode> parseArray(String resp, String errorPrefix) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            if (!root.isArray()) {
                throw new HubSpokeClient.HubCallException(errorPrefix + "：期望 JSON 数组");
            }
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        } catch (IOException e) {
            throw new HubSpokeClient.HubCallException(errorPrefix + "：" + e.getMessage(), e);
        }
    }

    /** 搜索候选（内部辅助，带排序权重） */
    private record SearchCandidate(String topic, String summary, String snippet, int weight) {
    }
}
