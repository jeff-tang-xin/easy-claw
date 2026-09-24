package com.xinl.easyclaw.blackboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.config.HubSpokeClient;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黑板 cloud 实现：读写全部委托 hub 的 {@code /api/spoke/blackboard/*} 端点，
 * spoke 本地不落任何黑板数据（防止两套数据分叉）。
 * <p>
 * 与本地实现的语义对齐点：append 返回可读的成败说明（不抛异常打断 AI 工具）、
 * 单条正文 4000 字符截断、read 按 seq 升序取最近 N 条、归档本只读且拒绝二次归档。
 * 差异点：hub 归档键格式为 {@code <key>.archived-<epochMillis>}（本地是本地时间戳），
 * 归档响应不含新键 —— 归档成功后回查 books 取刚生成的归档键。
 * <p>
 * <b>失败语义：</b>hub 不可达 / 非 2xx 时，append 返回失败说明、其余方法直接抛
 * {@link HubSpokeClient.HubCallException} 让调用方看到错误 ——
 * <b>绝不静默降级写本地</b>（否则 cloud 与本地两套数据会分叉）。
 */
@Component
public class CloudBlackboardStore implements BlackboardStore {

    private static final Logger log = LoggerFactory.getLogger(CloudBlackboardStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条正文上限（字符）：与 LocalBlackboardStore 一致，超出截断 */
    private static final int MAX_CONTENT_CHARS = 4_000;
    /** hub 归档键标记：归档 = book_key 整体改为 {@code <key>.archived-<epochMillis>} */
    private static final String ARCHIVED_MARK = ".archived-";

    private final HubSpokeClient hub;

    public CloudBlackboardStore(HubSpokeClient hub) {
        this.hub = hub;
    }

    @Override
    public String append(WorkspaceContext workspace, String key, String author, String type, String content) {
        if (isArchivedKey(key)) {
            // 归档本是只读历史：禁止再追加（hub 侧同样拒绝，这里提前给出与本地一致的文案）
            return "❌ 该记录本已归档，为只读历史，不能再登记内容。请改用当前活跃记录本。";
        }
        String body = truncate(content);
        // V24：projectId 必填（hub 端按项目落 blackboard_entries，与平台「黑板」同一份数据）；
        // 工作区未绑定项目属异常态，拒绝写入且绝不降级写本地（防两套数据分叉）
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return "❌ 记录本写入失败：工作区未绑定 hub 项目（projectId 缺失），请先完成项目绑定。";
        }
        String resp;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workspaceId", workspace.getWorkspaceId());
            payload.put("projectId", projectId);
            payload.put("key", key);
            payload.put("author", author == null ? "" : author);
            payload.put("type", type == null || type.isBlank() ? "note" : type);
            payload.put("content", body);
            resp = hub.post("/api/spoke/blackboard/entries", payload);
        } catch (HubSpokeClient.HubCallException e) {
            // 不吞异常：让调用方知道这条没写进 hub（也绝不降级写本地，防两套数据分叉）
            log.warn("hub 黑板追加失败: workspace={}, key={}, {}",
                    workspace.getWorkspaceId(), key, e.getMessage());
            return "❌ 记录本写入失败：" + e.getMessage() + "（本条未登记）";
        }
        long seq;
        try {
            seq = MAPPER.readTree(resp).path("seq").asLong(0);
        } catch (IOException e) {
            throw new HubSpokeClient.HubCallException("hub 黑板追加响应解析失败：" + e.getMessage(), e);
        }
        return "✅ #" + seq + " 已登记（" + type + ", by " + author + "）"
                + (body.length() < content.length() ? "；正文过长已截断" : "");
    }

    @Override
    public List<BlackboardEntry> read(WorkspaceContext workspace, String key, int limit) {
        int want = limit <= 0 ? DEFAULT_READ_LIMIT : Math.min(limit, MAX_READ_LIMIT);
        // V24：按 projectId 读（跨 source——人类在平台黑板写的记录 Agent 也能读到，团队协作）
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return List.of();
        }
        String resp = hub.get("/api/spoke/blackboard/entries?" + HubSpokeClient.query(Map.of(
                "projectId", projectId,
                "bookKey", key)));
        List<BlackboardEntry> all = new ArrayList<>();
        for (JsonNode n : parseArray(resp, "hub 黑板条目响应解析失败")) {
            all.add(new BlackboardEntry(
                    n.path("seq").asLong(0),
                    n.path("ts").asText(""),
                    n.path("author").asText(""),
                    n.path("type").asText(""),
                    n.path("content").asText("")));
        }
        // hub 已按 seq 升序返回；防御性再排一次，排序以 seq 为准（与本地一致）
        all.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        if (all.size() <= want) {
            return List.copyOf(all);
        }
        // 取最近 want 条，仍按 seq 升序
        return List.copyOf(all.subList(all.size() - want, all.size()));
    }

    @Override
    public List<BlackboardBook> listBooks(WorkspaceContext workspace) {
        // V24：按 projectId 读本清单（跨 source；归档本虚拟键 <key>.archived-<ts>）
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            return List.of();
        }
        String resp = hub.get("/api/spoke/blackboard/books?" + HubSpokeClient.query(Map.of(
                "projectId", projectId)));
        List<BlackboardBook> books = new ArrayList<>();
        for (JsonNode n : parseArray(resp, "hub 黑板清单响应解析失败")) {
            books.add(new BlackboardBook(
                    n.path("key").asText(""),
                    n.path("entryCount").asLong(0),
                    n.path("lastModified").asLong(0),
                    n.path("archived").asBoolean(false),
                    n.path("archivedAt").asLong(0)));
        }
        books.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return List.copyOf(books);
    }

    @Override
    public String archiveBook(WorkspaceContext workspace, String key) {
        if (isArchivedKey(key)) {
            // 归档本已经是历史快照，不允许二次归档（与本地语义一致）
            throw new IllegalArgumentException("该记录本已归档，为只读历史，不能再次归档");
        }
        // V24：按 projectId 归档（仅归档 source=workspace 的活跃条目，平台条目不受影响）
        Long projectId = workspace.getProjectId();
        if (projectId == null) {
            throw new IllegalArgumentException("工作区未绑定 hub 项目（projectId 缺失），请先完成项目绑定");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workspaceId", workspace.getWorkspaceId());
            payload.put("projectId", projectId);
            payload.put("key", key);
            hub.post("/api/spoke/blackboard/archive", payload);
        } catch (HubSpokeClient.HubCallException e) {
            if (e.statusCode() != null && e.statusCode() == 404) {
                // 记录本不存在：与本地一致返回 null（管理页面提示不存在）
                return null;
            }
            throw e;
        }
        // hub 归档响应不含新键（归档键由 hub 生成：<key>.archived-<epochMillis>），
        // 回查 books 取刚生成的归档键：archived=true 且 archivedAt 最大者即本次归档件。
        for (BlackboardBook b : listBooks(workspace)) {
            if (b.archived() && b.key().startsWith(key + ARCHIVED_MARK)) {
                return b.key();
            }
        }
        throw new HubSpokeClient.HubCallException("归档已完成但未能从 hub 确认归档键: " + key);
    }

    /** hub 归档键判定：与 hub 侧规则一致 —— 含 {@code .archived-} 即视为归档句柄 */
    private static boolean isArchivedKey(String key) {
        return key != null && key.contains(ARCHIVED_MARK);
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

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS) + "…[truncated]";
    }
}
