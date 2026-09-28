package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.tool.OrgToolSettingDto;
import com.xinl.easyclaw.hub.contract.tool.PlatformToolDto;
import com.xinl.easyclaw.hub.contract.tool.SetToolEnabledRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeToolInfo;
import com.xinl.easyclaw.hub.entity.OrgToolSettingEntity;
import com.xinl.easyclaw.hub.entity.PlatformToolEntity;
import com.xinl.easyclaw.hub.repository.OrgToolSettingRepository;
import com.xinl.easyclaw.hub.repository.PlatformToolRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台工具目录 + 组织启用开关：
 * <ul>
 *   <li>目录（platform_tools）由 Java Seeder 对齐 web/api ToolRegistry 播种（38 个内置工具），
 *       不经 API 增删；platformAdmin 仅可改平台总开关（enabled）；</li>
 *   <li>组织侧（org_tool_settings）只决定「是否启用」：惰性行，无行 = 默认启用，owner/admin 写、成员读；</li>
 *   <li>生效语义（唯一裁决口径）= 平台 enabled AND 组织 enabled，spoke 下发按此过滤。</li>
 * </ul>
 */
@Service
public class ToolCatalogService {

    /** 内置工具种子：toolKey 对齐 web/api ToolRegistry 工具名。 */
    private record Seed(String key, String displayName, String description, String group, int sortOrder) {
    }

    /** 38 个内置工具 = 22 框架 + 16 自定义 @Tool，分组与 web/api ToolRegistryService 对齐。 */
    private static final List<Seed> SEEDS = List.of(
            // ---- 框架工具（22）----
            new Seed("read_file", "读取文件", "读取工作区内文件内容（支持分页）", "文件", 10),
            new Seed("write_file", "写入文件", "新建或覆盖写入工作区文件", "文件", 20),
            new Seed("edit_file", "编辑文件", "对既有文件做精确字符串替换", "文件", 30),
            new Seed("glob_files", "按模式找文件", "按 glob 模式递归匹配文件路径", "文件", 40),
            new Seed("grep_files", "按内容搜文件", "在文件内容中做字面量子串搜索", "文件", 50),
            new Seed("search_files", "按名称搜文件", "按文件名包含关键词递归搜索", "文件", 60),
            new Seed("list_directory", "浏览目录", "列出目录下的文件与子目录", "文件", 70),
            new Seed("list_files", "列出文件", "列出指定路径下的文件", "文件", 80),
            new Seed("execute", "执行命令", "在工作区内执行 shell 命令", "执行", 90),
            new Seed("run_python", "运行 Python", "执行一段 Python 3 代码并返回输出", "执行", 100),
            new Seed("run_skill_script", "运行技能脚本", "运行已加载 Skill 自带的脚本", "执行", 110),
            new Seed("analyze_code", "分析代码", "统计代码结构指标（行数/函数/类/嵌套）", "代码", 120),
            new Seed("diff_code", "对比代码", "输出两段代码的 unified diff 差异", "代码", 130),
            new Seed("format_code", "格式化代码", "对代码文本做基础格式化", "代码", 140),
            new Seed("inspect_data", "校验数据", "校验 JSON/CSV 的结构与数据质量", "数据", 150),
            new Seed("web_search", "网络搜索", "搜索互联网并返回结果摘要", "网络", 160),
            new Seed("web_fetch", "抓取网页", "抓取指定 URL 的文本内容", "网络", 170),
            new Seed("fetch_webpage", "读取网页", "获取网页正文（去除 HTML 标签）", "网络", 180),
            new Seed("knowledge_search", "知识库搜索", "在知识库中按关键词全文检索", "知识", 190),
            new Seed("knowledge_read", "读取知识", "读取指定知识条目的完整正文", "知识", 200),
            new Seed("knowledge_write", "写入知识", "把结论写入知识库成为可复用条目", "知识", 210),
            new Seed("knowledge_list", "知识清单", "列出知识库全部条目与摘要", "知识", 220),
            // ---- 自定义 @Tool（16）----
            new Seed("memory_search", "记忆检索", "在长期记忆文件中检索历史信息", "记忆", 230),
            new Seed("memory_get", "读取记忆", "读取记忆文件的指定行区间", "记忆", 240),
            new Seed("memory_save", "保存记忆", "把事实持久化到长期记忆", "记忆", 250),
            new Seed("blackboard_read", "读黑板", "读取共享黑板最近条目", "协作", 260),
            new Seed("blackboard_append", "写黑板", "向共享黑板追加一条结论", "协作", 270),
            new Seed("session_search", "会话检索", "在历史会话记录中搜索关键词", "会话", 280),
            new Seed("session_list", "会话清单", "列出某 Agent 的可用会话", "会话", 290),
            new Seed("session_history", "会话历史", "读取指定会话的消息历史", "会话", 300),
            new Seed("agent_spawn", "派生子任务", "派发隔离子 Agent 执行子任务", "协作", 310),
            new Seed("agent_send", "子任务通信", "向既有子 Agent 发送后续消息", "协作", 320),
            new Seed("agent_list", "子任务清单", "列出当前活跃的子 Agent", "协作", 330),
            new Seed("task_output", "取任务结果", "获取后台任务的输出结果", "任务", 340),
            new Seed("task_list", "任务清单", "列出当前会话的后台任务", "任务", 350),
            new Seed("task_cancel", "取消任务", "取消一个运行中的后台任务", "任务", 360),
            new Seed("wait_async_results", "等待任务", "等待后台任务完成并收集结果", "任务", 370),
            new Seed("load_skill_through_path", "加载技能", "按路径加载 Skill 资源文档", "技能", 380));

    private final PlatformToolRepository tools;
    private final OrgToolSettingRepository settings;
    private final OrgService orgService;
    private final AuditService auditService;
    private final PlatformAdminGuard platformAdmin;

    public ToolCatalogService(PlatformToolRepository tools, OrgToolSettingRepository settings,
                              OrgService orgService, AuditService auditService, PlatformAdminGuard platformAdmin) {
        this.tools = tools;
        this.settings = settings;
        this.orgService = orgService;
        this.auditService = auditService;
        this.platformAdmin = platformAdmin;
    }

    // ---------- 平台目录（只读 + 总开关） ----------

    /** 平台工具目录（按 sort_order,id）。 */
    @Transactional(readOnly = true)
    public List<PlatformToolDto> listCatalog(Long requesterId) {
        platformAdmin.require(requesterId);
        return tools.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toDto).toList();
    }

    /** 平台工具总开关（唯一可改字段）。 */
    @Transactional
    public PlatformToolDto setEnabled(Long requesterId, Long id, SetToolEnabledRequest req) {
        platformAdmin.require(requesterId);
        PlatformToolEntity t = requireTool(id);
        t.setEnabled(req.enabled());
        tools.save(t);
        auditService.record(AuditModule.TOOL, req.enabled() ? "enable_platform_tool" : "disable_platform_tool",
                requesterId, null, "tool", String.valueOf(t.getId()), "key=" + t.getToolKey(),
                AuditModule.SUCCESS);
        return toDto(t);
    }

    // ---------- 组织启用开关（读=成员，写=owner/admin） ----------

    /** 组织工具启用态：全量目录 × 该组织生效态（无开关行 = 默认启用）。 */
    @Transactional(readOnly = true)
    public List<OrgToolSettingDto> listSettings(Long requesterId, Long orgId) {
        requireMember(orgId, requesterId);
        Map<Long, Boolean> enabledByTool = new HashMap<>();
        for (OrgToolSettingEntity s : settings.findByOrgId(orgId)) {
            enabledByTool.put(s.getToolId(), s.getEnabled());
        }
        List<OrgToolSettingDto> out = new ArrayList<>();
        for (PlatformToolEntity t : tools.findAllByOrderBySortOrderAscIdAsc()) {
            out.add(new OrgToolSettingDto(t.getId(), t.getToolKey(), t.getDisplayName(),
                    enabledByTool.getOrDefault(t.getId(), true)));
        }
        return out;
    }

    /** 幂等 upsert 组织启用行；toolId 不存在 → 404。 */
    @Transactional
    public void setEnabled(Long requesterId, Long orgId, Long toolId, boolean enabled) {
        requireWriter(orgId, requesterId);
        requireTool(toolId);
        OrgToolSettingEntity s = settings.findByOrgIdAndToolId(orgId, toolId).orElseGet(() -> {
            OrgToolSettingEntity n = new OrgToolSettingEntity();
            n.setOrgId(orgId);
            n.setToolId(toolId);
            return n;
        });
        s.setEnabled(enabled);
        settings.save(s);
        auditService.record(AuditModule.TOOL, "set_org_tool_enabled", requesterId, orgId, "tool",
                String.valueOf(toolId), "enabled=" + enabled, AuditModule.SUCCESS);
    }

    // ---------- 下发用（供 SpokeService 复用，只读，无请求者上下文） ----------

    /**
     * 某组织的生效工具列表（GET /api/spoke/tools）：仅平台总开关开启的条目，
     * enabled = 平台 enabled AND 组织 enabled。
     */
    @Transactional(readOnly = true)
    public List<SpokeToolInfo> effectiveTools(Long orgId) {
        Map<Long, Boolean> enabledByTool = new HashMap<>();
        for (OrgToolSettingEntity s : settings.findByOrgId(orgId)) {
            enabledByTool.put(s.getToolId(), s.getEnabled());
        }
        List<SpokeToolInfo> out = new ArrayList<>();
        for (PlatformToolEntity t : tools.findAllByOrderBySortOrderAscIdAsc()) {
            if (Boolean.TRUE.equals(t.getEnabled())) {
                boolean orgEnabled = enabledByTool.getOrDefault(t.getId(), true);
                out.add(new SpokeToolInfo(t.getToolKey(), orgEnabled));
            }
        }
        return out;
    }

    // ---------- 播种（应用启动时调用一次） ----------

    /** 按 toolKey 幂等播种内置工具：缺失则插入，已存在不覆盖（保留平台开关与排序的人工调整）。 */
    @Transactional
    public void seedBuiltinTools() {
        for (Seed seed : SEEDS) {
            if (tools.existsByToolKey(seed.key())) {
                continue;
            }
            PlatformToolEntity t = new PlatformToolEntity();
            t.setToolKey(seed.key());
            t.setDisplayName(seed.displayName());
            t.setDescription(seed.description());
            t.setToolGroup(seed.group());
            t.setSortOrder(seed.sortOrder());
            t.setEnabled(true);
            tools.save(t);
        }
    }

    // ---------- 鉴权 ----------

    private void requireMember(Long orgId, Long userId) {
        if (orgService.roleOf(orgId, userId) == null) {
            throw ApiException.forbidden("非组织成员");
        }
    }

    private void requireWriter(Long orgId, Long userId) {
        orgService.requireOrgRole(orgId, userId, "owner", "admin");
    }

    // ---------- 内部 ----------

    private PlatformToolEntity requireTool(Long id) {
        return tools.findById(id).orElseThrow(() -> ApiException.notFound("工具不存在"));
    }

    private PlatformToolDto toDto(PlatformToolEntity t) {
        return new PlatformToolDto(t.getId(), t.getToolKey(), t.getDisplayName(), t.getDescription(),
                t.getToolGroup(), t.getSortOrder(), t.getEnabled());
    }
}
