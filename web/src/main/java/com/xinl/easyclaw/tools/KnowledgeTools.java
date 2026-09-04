package com.xinl.easyclaw.tools;

import com.xinl.easyclaw.knowledge.KnowledgeEntry;
import com.xinl.easyclaw.knowledge.KnowledgeService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库工具：把有长期价值的结论沉淀成跨会话可用的知识条目。
 * <p>
 * 与 {@link BlackboardTools} 的分工是本设计的核心：
 * <ul>
 *   <li><b>blackboard</b> = 会话级、高频、append-only 的协作草稿。
 *       只在本次任务内有效，任务结束即可归档清空。</li>
 *   <li><b>knowledge</b> = 跨会话、精炼、可覆盖的长期知识。
 *       每次请求由框架自动注入系统提示，是「下次不必重新调研」的载体。</li>
 * </ul>
 * 两者之间的通道叫<b>晋升</b>：会话中用 blackboard_append 记草稿，
 * 收尾时把真正有复用价值的结论用 knowledge_write 提炼成条目。
 * <p>
 * <b>为什么必须提供 knowledge_read：</b>{@code knowledge/} 位于 {@code .easyClaw}
 * 之下，而该目录在 {@code forbidden-paths} 内 —— AI 用 read_file 读不到它。
 * 框架 guidance 虽写着「用 read_file 按需打开」，在本项目的沙箱配置下并不成立，
 * 故必须由本工具补上读取能力，否则索引里列出的条目全部无法展开。
 * <p>
 * WorkspaceContext 由框架注入，不暴露给 LLM。
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    /** 列表渲染上限（字符）：条目过多时截断，避免挤占上下文 */
    private static final int MAX_RENDER_CHARS = 8_000;

    private final KnowledgeService knowledgeService;

    public KnowledgeTools(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "knowledge_write", description = "把一条有长期价值的结论写入本地知识库，成为跨会话可复用的知识条目（下次对话会自动注入系统提示）。\n"
            + "【何时用】任务收尾时把值得留存的结论「晋升」进知识库：调研结果、架构决策、踩坑记录、数据口径、环境配置要点。\n"
            + "【不要用于】本次任务内的临时协作草稿（那用 blackboard_append）；也不要用于记录过程日志或未验证的猜测。\n"
            + "【参数】topic：条目名，全局唯一，只用字母数字和 - _，建议用「分类-主题」式命名（如 rag-design、env-build-notes）；同名会覆盖，先用 knowledge_list 查重。summary：一句话摘要，会显示在索引里。content：正文，Markdown 格式。")
    public String knowledgeWrite(
            @ToolParam(name = "topic", description = "条目名（全局唯一，字母数字与 - _，建议「分类-主题」式命名）") String topic,
            @ToolParam(name = "summary", description = "一句话摘要，显示在知识库索引中") String summary,
            @ToolParam(name = "content", description = "正文内容（Markdown 格式）") String content,
            WorkspaceContext workspace) {
        if (workspace == null) {
            return "❌ 当前没有可用的工作区，无法写入知识库。";
        }
        try {
            boolean existed = knowledgeService.exists(topic, workspace);
            String result = knowledgeService.write(topic, summary, content, workspace);
            if (existed && result.startsWith("✅")) {
                return result + "（已覆盖同名条目）";
            }
            return result;
        } catch (Exception e) {
            log.error("写入知识库失败", e);
            return "❌ 写入失败: " + e.getMessage();
        }
    }

    @Tool(name = "knowledge_list", description = "列出本地知识库里已有的所有知识条目（条目名 + 摘要）。\n"
            + "【何时用】写入新条目前先查重，避免重复造同一条知识；需要了解「已经沉淀过什么」时。\n"
            + "【不要用于】读取条目正文（那用 knowledge_read）。\n"
            + "【参数】无。")
    public String knowledgeList(WorkspaceContext workspace) {
        if (workspace == null) {
            return "❌ 当前没有可用的工作区，无法读取知识库。";
        }
        try {
            List<KnowledgeEntry> entries = knowledgeService.list(workspace);
            if (entries.isEmpty()) {
                return "（知识库为空：还没有沉淀任何条目。有值得长期留存的结论时请用 knowledge_write 写入。）";
            }
            StringBuilder sb = new StringBuilder("知识库共 " + entries.size() + " 条：\n\n");
            for (KnowledgeEntry e : entries) {
                String line = "- " + e.topic() + " — " + e.summary() + "\n";
                if (sb.length() + line.length() > MAX_RENDER_CHARS) {
                    sb.append("…（条目较多，已截断）\n");
                    break;
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("列出知识库失败", e);
            return "❌ 读取失败: " + e.getMessage();
        }
    }

    @Tool(name = "knowledge_read", description = "读取指定知识条目的完整正文。\n"
            + "【何时用】系统提示的知识库索引里看到相关条目、需要展开细节时；或写入前想看看旧版本内容。\n"
            + "【不要用于】用 read_file 读知识库文件——knowledge 目录在沙箱禁止路径内，read_file 读不到，必须用本工具。\n"
            + "【参数】topic：条目名（从 knowledge_list 或系统提示的索引里取）。")
    public String knowledgeRead(
            @ToolParam(name = "topic", description = "要读取的条目名") String topic,
            WorkspaceContext workspace) {
        if (workspace == null) {
            return "❌ 当前没有可用的工作区，无法读取知识库。";
        }
        if (topic == null || topic.isBlank()) {
            return "❌ topic 不能为空。";
        }
        try {
            String content = knowledgeService.read(topic, workspace);
            if (content.isEmpty()) {
                return "❌ 条目「" + topic + "」不存在。请先用 knowledge_list 查看已有条目。";
            }
            return content;
        } catch (Exception e) {
            log.error("读取知识条目失败", e);
            return "❌ 读取失败: " + e.getMessage();
        }
    }
}