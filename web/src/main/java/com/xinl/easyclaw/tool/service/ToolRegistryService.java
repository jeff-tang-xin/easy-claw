package com.xinl.easyclaw.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tools.CodeGenerationTools;
import com.xinl.easyclaw.tools.FileOperationTools;
import com.xinl.easyclaw.tools.WebSearchTools;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 系统内置工具注册表
 * <p>
 * 通过反射扫描 {@code @Tool} 注解方法，提供系统内支持的完整工具清单
 * （名称 / 显示名 / 描述 / 分组 / 参数定义），并将启用状态持久化到
 * {@code tool_definitions} 表（Agent 的 Toolkit 按启用状态过滤注册）。
 */
@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolManagementService toolService;
    private final FileOperationTools fileTools;
    private final WebSearchTools searchTools;
    private final CodeGenerationTools codeTools;

    /** 工具参数定义 */
    public record ToolParamDef(String name, boolean required, String description) {
    }

    /** 工具定义 */
    public record ToolDef(String name, String displayName, String description,
                          String group, List<ToolParamDef> params) {
    }

    /** 框架工具可读描述（供前端工具目录展示） */
    private static final Map<String, String> FRAMEWORK_DESC = Map.ofEntries(
            Map.entry("read_file", "读取工作区内文件内容，支持 offset/limit 分页。用于查看源码、配置、文档等。"),
            Map.entry("write_file", "创建或覆盖写入文件。用于生成代码、配置、文档等。\n"
                    + "【重要·长度限制】单次写入内容过长会被截断或失败。生成大文件（约 300 行 / 15KB 以上）时"
                    + "必须分段：先用 write_file 写入第一段（骨架或前若干部分），再用 edit_file 逐段追加剩余内容，"
                    + "禁止一次性提交超长内容。\n"
                    + "【不要用于】局部修改已有文件（用 edit_file，避免整文件重写丢失内容）。"),
            Map.entry("edit_file", "局部编辑文件（按旧文本→新文本替换）。用于精确修改而非整文件重写。"),
            Map.entry("grep_files", "按正则/关键词搜索文件内容，返回匹配行与行号。用于定位代码、查找引用。"),
            Map.entry("glob_files", "按文件名通配符匹配文件路径。用于按扩展名或命名模式批量查找。"),
            Map.entry("list_files", "列出目录下的文件和子目录。用于快速了解目录结构。"),
            Map.entry("memory_search", "搜索 Agent 长期记忆库。用于回忆过往交互或用户偏好。"),
            Map.entry("memory_get", "按 ID 获取单条记忆。用于精确读取特定记忆条目。"),
            Map.entry("session_search", "搜索历史会话记录。用于查找过去某次对话。"),
            Map.entry("session_list", "列出历史会话列表。用于查看有哪些会话。"),
            Map.entry("session_history", "获取指定会话的完整消息历史。用于回顾某次对话的全部内容。"),
            Map.entry("agent_spawn", "创建子 Agent 并派发任务。用于多 Agent 协作场景。"),
            Map.entry("agent_send", "向运行中的子 Agent 发送消息。用于主从 Agent 间通信。"),
            Map.entry("agent_list", "列出当前活跃的子 Agent。用于查看已创建的子 Agent。"),
            Map.entry("task_output", "获取异步任务输出。用于读取子 Agent 的执行结果。"),
            Map.entry("task_cancel", "取消正在运行的异步任务。用于中止子 Agent 执行。"),
            Map.entry("task_list", "列出所有异步任务。用于查看任务执行状态。"),
            Map.entry("execute", "在沙箱内执行 Shell 命令。用于运行构建、测试、git 等命令行操作。")
    );

    /** 框架 FilesystemTool 内置工具名（HarnessAgent 自动注册，不可禁用） */
    static final Set<String> FRAMEWORK_FILESYSTEM_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files"
    );

    /** 框架 Memory 内置工具名 */
    static final Set<String> FRAMEWORK_MEMORY_TOOLS = Set.of(
            "memory_search", "memory_get"
    );

    /** 框架 Session 内置工具名 */
    static final Set<String> FRAMEWORK_SESSION_TOOLS = Set.of(
            "session_search", "session_list", "session_history"
    );

    /** 框架 Subagent 内置工具名（非叶子 Agent 自动注册） */
    static final Set<String> FRAMEWORK_SUBAGENT_TOOLS = Set.of(
            "agent_spawn", "agent_send", "agent_list", "task_output", "task_cancel", "task_list"
    );

    /** 框架 Shell 内置工具名（后端是 AbstractSandboxFilesystem 时自动注册） */
    static final Set<String> FRAMEWORK_SHELL_TOOLS = Set.of("execute");

    /** 所有框架内置工具名的并集 */
    static final Set<String> ALL_FRAMEWORK_TOOLS;

    static {
        Set<String> all = new HashSet<>();
        all.addAll(FRAMEWORK_FILESYSTEM_TOOLS);
        all.addAll(FRAMEWORK_MEMORY_TOOLS);
        all.addAll(FRAMEWORK_SESSION_TOOLS);
        all.addAll(FRAMEWORK_SUBAGENT_TOOLS);
        all.addAll(FRAMEWORK_SHELL_TOOLS);
        ALL_FRAMEWORK_TOOLS = Set.copyOf(all);
    }

    private static final Map<String, String> DISPLAY = Map.ofEntries(
            // 框架 FilesystemTool 内置工具
            Map.entry("read_file", "文件读取（框架）"),
            Map.entry("write_file", "文件写入（框架）"),
            Map.entry("edit_file", "文件编辑（框架）"),
            Map.entry("grep_files", "内容搜索（框架）"),
            Map.entry("glob_files", "文件匹配（框架）"),
            Map.entry("list_files", "文件列表（框架）"),
            // 框架 Memory 内置工具
            Map.entry("memory_search", "记忆搜索（框架）"),
            Map.entry("memory_get", "记忆获取（框架）"),
            // 框架 Session 内置工具
            Map.entry("session_search", "会话搜索（框架）"),
            Map.entry("session_list", "会话列表（框架）"),
            Map.entry("session_history", "会话历史（框架）"),
            // 框架 Subagent 内置工具
            Map.entry("agent_spawn", "子Agent创建（框架）"),
            Map.entry("agent_send", "Agent通信（框架）"),
            Map.entry("agent_list", "Agent列表（框架）"),
            Map.entry("task_output", "任务输出（框架）"),
            Map.entry("task_cancel", "任务取消（框架）"),
            Map.entry("task_list", "任务列表（框架）"),
            // 框架 Shell 内置工具
            Map.entry("execute", "Shell执行（框架）"),
            // 自定义补充工具
            Map.entry("list_directory", "目录列表（增强）"),
            Map.entry("search_files", "文件搜索（按名称）"),
            Map.entry("analyze_code", "代码分析"),
            Map.entry("format_code", "代码格式化"),
            Map.entry("diff_code", "代码对比"),
            Map.entry("inspect_data", "数据校验"),
            Map.entry("run_python", "Python 执行"),
            Map.entry("web_search", "网络搜索"),
            Map.entry("fetch_webpage", "网页获取")
    );

    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("read_file", "FILE"),
            Map.entry("write_file", "FILE"),
            Map.entry("edit_file", "FILE"),
            Map.entry("grep_files", "FILE"),
            Map.entry("glob_files", "FILE"),
            Map.entry("list_files", "FILE"),
            Map.entry("list_directory", "FILE"),
            Map.entry("search_files", "FILE"),
            Map.entry("analyze_code", "CODE"),
            Map.entry("format_code", "CODE"),
            Map.entry("diff_code", "CODE"),
            Map.entry("inspect_data", "CODE"),
            Map.entry("run_python", "CODE"),
            Map.entry("web_search", "WEB"),
            Map.entry("fetch_webpage", "WEB"),
            Map.entry("memory_search", "MEMORY"),
            Map.entry("memory_get", "MEMORY"),
            Map.entry("session_search", "SESSION"),
            Map.entry("session_list", "SESSION"),
            Map.entry("session_history", "SESSION"),
            Map.entry("agent_spawn", "AGENT"),
            Map.entry("agent_send", "AGENT"),
            Map.entry("agent_list", "AGENT"),
            Map.entry("task_output", "AGENT"),
            Map.entry("task_cancel", "AGENT"),
            Map.entry("task_list", "AGENT"),
            Map.entry("execute", "SHELL")
    );

    public ToolRegistryService(ToolManagementService toolService,
                               FileOperationTools fileTools,
                               WebSearchTools searchTools,
                               CodeGenerationTools codeTools) {
        this.toolService = toolService;
        this.fileTools = fileTools;
        this.searchTools = searchTools;
        this.codeTools = codeTools;
    }

    /**
     * 首次启动时把系统内置工具同步到 tool_definitions 表（名称 = @Tool 名）
     */
    @PostConstruct
    public void syncBuiltinTools() {
        try {
            for (ToolDef def : listBuiltinTools()) {
                if (toolService.findByName(def.name()).isEmpty()) {
                    toolService.create(ToolDefinitionEntity.builder()
                            .name(def.name())
                            .displayName(def.displayName())
                            .description(def.description())
                            .toolGroup(def.group())
                            .parameters(toJson(def.params()))
                            .implementation("BUILTIN")
                            .implementationConfig(def.name())
                            .enabled(true)
                            .isSystem(true)
                            .build());
                }
            }
            log.info("系统内置工具已同步: {} 个", listBuiltinTools().size());
        } catch (Exception e) {
            log.warn("同步内置工具失败: {}", e.getMessage());
        }
    }

    /**
     * 系统内支持的工具清单（框架内置 + 自定义 @Tool 注解）
     */
    public List<ToolDef> listBuiltinTools() {
        List<ToolDef> defs = new ArrayList<>();

        // 1. 框架内置工具（无 @Tool 注解，AgentScope 自动注册）
        for (String name : ALL_FRAMEWORK_TOOLS) {
            defs.add(new ToolDef(name,
                    DISPLAY.getOrDefault(name, name),
                    FRAMEWORK_DESC.getOrDefault(name, "框架内置工具"),
                    GROUPS.getOrDefault(name, "FRAMEWORK"),
                    List.of()));
        }

        // 2. 自定义补充工具（反射 @Tool 注解）
        for (Object instance : List.of(fileTools, searchTools, codeTools)) {
            for (Method m : instance.getClass().getMethods()) {
                Tool tool = m.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                String name = tool.name();
                // 避免重复（如果自定义工具名恰好和框架重名）
                if (ALL_FRAMEWORK_TOOLS.contains(name)) {
                    continue;
                }
                List<ToolParamDef> params = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    ToolParam tp = p.getAnnotation(ToolParam.class);
                    if (tp != null) {
                        params.add(new ToolParamDef(tp.name(), tp.required(), tp.description()));
                    }
                }
                defs.add(new ToolDef(name,
                        DISPLAY.getOrDefault(name, name),
                        tool.description(),
                        GROUPS.getOrDefault(name, "GENERAL"),
                        params));
            }
        }
        defs.sort(Comparator.comparing(ToolDef::group).thenComparing(ToolDef::name));
        return defs;
    }

    /**
     * 当前被禁用的内置工具名（Agent Toolkit 注册时过滤）
     * <p>框架内置工具（FilesystemTool / Memory / Session / Subagent / Shell）
     * 由 HarnessAgent 自动注册，用户无法禁用，因此不会出现在这里。
     */
    public List<String> disabledToolNames() {
        return toolService.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getEnabled()))
                .map(ToolDefinitionEntity::getName)
                .filter(name -> !ALL_FRAMEWORK_TOOLS.contains(name))
                .toList();
    }

    /**
     * 判断工具是否为框架内置（HarnessAgent 自动注册，不可禁用）
     */
    public static boolean isFrameworkTool(String toolName) {
        return ALL_FRAMEWORK_TOOLS.contains(toolName);
    }

    /**
     * 查询某个工具的启用状态
     * <p>框架内置工具始终返回 true（不可禁用）。
     */
    public boolean isEnabled(String toolName) {
        if (ALL_FRAMEWORK_TOOLS.contains(toolName)) {
            return true;
        }
        return toolService.findByName(toolName)
                .map(t -> Boolean.TRUE.equals(t.getEnabled()))
                .orElse(true);
    }

    /**
     * 工具名是否已注册（框架内置或 DB 中登记过）。
     * <p>用于确认端点的白名单校验：拒绝把未知工具名写进永久授权规则。
     */
    public boolean isRegistered(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (ALL_FRAMEWORK_TOOLS.contains(toolName)) {
            return true;
        }
        return toolService.findByName(toolName).isPresent();
    }

    private String toJson(List<ToolParamDef> params) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        List<Map<String, Object>> props = new ArrayList<>();
        for (ToolParamDef p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("name", p.name());
            prop.put("required", p.required());
            prop.put("description", p.description());
            props.add(prop);
        }
        root.put("parameters", props);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
