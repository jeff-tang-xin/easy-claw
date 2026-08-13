package com.xinl.easyclaw.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tools.CodeGenerationTools;
import com.xinl.easyclaw.tools.FileOperationTools;
import com.xinl.easyclaw.tools.WebSearchTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolManagementService toolService;
    private final FileOperationTools fileTools;
    private final WebSearchTools searchTools;
    private final CodeGenerationTools codeTools;

    public record ToolParamDef(String name, boolean required, String description) {
    }

    public record ToolDef(String name, String displayName, String description,
                          String group, List<ToolParamDef> params) {
    }

    static final Set<String> FRAMEWORK_FILESYSTEM_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files"
    );

    static final Set<String> FRAMEWORK_MEMORY_TOOLS = Set.of(
            "memory_search", "memory_get"
    );

    static final Set<String> FRAMEWORK_SESSION_TOOLS = Set.of(
            "session_search", "session_list", "session_history"
    );

    static final Set<String> FRAMEWORK_SUBAGENT_TOOLS = Set.of(
            "agent_spawn", "agent_send", "agent_list", "task_output", "task_cancel", "task_list"
    );

    static final Set<String> FRAMEWORK_SHELL_TOOLS = Set.of("execute");

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
            Map.entry("read_file", "文件读取（框架）"),
            Map.entry("write_file", "文件写入（框架）"),
            Map.entry("edit_file", "文件编辑（框架）"),
            Map.entry("grep_files", "内容搜索（框架）"),
            Map.entry("glob_files", "文件匹配（框架）"),
            Map.entry("list_files", "文件列表（框架）"),
            Map.entry("memory_search", "记忆搜索（框架）"),
            Map.entry("memory_get", "记忆获取（框架）"),
            Map.entry("session_search", "会话搜索（框架）"),
            Map.entry("session_list", "会话列表（框架）"),
            Map.entry("session_history", "会话历史（框架）"),
            Map.entry("agent_spawn", "子Agent创建（框架）"),
            Map.entry("agent_send", "Agent通信（框架）"),
            Map.entry("agent_list", "Agent列表（框架）"),
            Map.entry("task_output", "任务输出（框架）"),
            Map.entry("task_cancel", "任务取消（框架）"),
            Map.entry("task_list", "任务列表（框架）"),
            Map.entry("execute", "Shell执行（框架）"),
            Map.entry("list_directory", "目录列表（增强）"),
            Map.entry("search_files", "文件搜索（按名称）"),
            Map.entry("analyze_code", "代码分析"),
            Map.entry("format_code", "代码格式化"),
            Map.entry("diff_code", "代码对比"),
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

    public List<ToolDef> listBuiltinTools() {
        List<ToolDef> defs = new ArrayList<>();

        for (String name : ALL_FRAMEWORK_TOOLS) {
            defs.add(new ToolDef(name,
                    DISPLAY.getOrDefault(name, name),
                    "框架内置工具",
                    GROUPS.getOrDefault(name, "FRAMEWORK"),
                    List.of()));
        }

        /* TODO: migrate to Embabel - reflection scan removed, tool names hardcoded */
        for (Object instance : List.of(fileTools, searchTools, codeTools)) {
            for (Method m : instance.getClass().getMethods()) {
                ToolDef def = tryBuildToolDef(m);
                if (def != null && !ALL_FRAMEWORK_TOOLS.contains(def.name())) {
                    defs.add(def);
                }
            }
        }
        defs.sort(Comparator.comparing(ToolDef::group).thenComparing(ToolDef::name));
        return defs;
    }

    private ToolDef tryBuildToolDef(Method m) {
        String methodName = m.getName();
        switch (methodName) {
            case "listDirectory" -> {
                return new ToolDef("list_directory",
                        DISPLAY.getOrDefault("list_directory", "list_directory"),
                        "列出当前工作区内指定目录下的所有文件和子目录（带图标区分）",
                        GROUPS.getOrDefault("list_directory", "FILE"),
                        List.of(new ToolParamDef("path", false, "目录路径（相对当前工作区根目录，留空表示根目录）")));
            }
            case "searchFiles" -> {
                return new ToolDef("search_files",
                        DISPLAY.getOrDefault("search_files", "search_files"),
                        "在当前工作区内按文件名关键词搜索文件，返回匹配的路径列表",
                        GROUPS.getOrDefault("search_files", "FILE"),
                        List.of(new ToolParamDef("keyword", true, "搜索关键词（文件名包含）")));
            }
            case "webSearch" -> {
                return new ToolDef("web_search",
                        DISPLAY.getOrDefault("web_search", "web_search"),
                        "通过搜索引擎检索最新信息，返回相关摘要。适用于需要实时数据（新闻、版本号、价格、API 文档等）的问题。",
                        GROUPS.getOrDefault("web_search", "WEB"),
                        List.of(new ToolParamDef("query", true, "搜索关键词")));
            }
            case "fetchWebpage" -> {
                return new ToolDef("fetch_webpage",
                        DISPLAY.getOrDefault("fetch_webpage", "fetch_webpage"),
                        "获取指定 URL 的网页文本内容（自动去除 HTML 标签）。适合读取文档、API 说明、博客文章等。",
                        GROUPS.getOrDefault("fetch_webpage", "WEB"),
                        List.of(new ToolParamDef("url", true, "要获取的完整 URL")));
            }
            case "analyzeCode" -> {
                return new ToolDef("analyze_code",
                        DISPLAY.getOrDefault("analyze_code", "analyze_code"),
                        "分析给定代码的复杂度，返回代码统计信息（行数、方法数、类数等）",
                        GROUPS.getOrDefault("analyze_code", "CODE"),
                        List.of(new ToolParamDef("code", true, "要分析的代码内容"),
                                new ToolParamDef("language", true, "编程语言，如 Java、Python、JavaScript")));
            }
            case "formatCode" -> {
                return new ToolDef("format_code",
                        DISPLAY.getOrDefault("format_code", "format_code"),
                        "将代码格式化为标准风格，移除多余空行和空格",
                        GROUPS.getOrDefault("format_code", "CODE"),
                        List.of(new ToolParamDef("code", true, "要格式化的代码内容")));
            }
            case "diffCode" -> {
                return new ToolDef("diff_code",
                        DISPLAY.getOrDefault("diff_code", "diff_code"),
                        "对比两段代码的差异，返回不同之处的描述",
                        GROUPS.getOrDefault("diff_code", "CODE"),
                        List.of(new ToolParamDef("code1", true, "第一段代码"),
                                new ToolParamDef("code2", true, "第二段代码")));
            }
            default -> {
                return null;
            }
        }
    }

    public List<String> disabledToolNames() {
        return toolService.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getEnabled()))
                .map(ToolDefinitionEntity::getName)
                .filter(name -> !ALL_FRAMEWORK_TOOLS.contains(name))
                .toList();
    }

    public static boolean isFrameworkTool(String toolName) {
        return ALL_FRAMEWORK_TOOLS.contains(toolName);
    }

    public boolean isEnabled(String toolName) {
        if (ALL_FRAMEWORK_TOOLS.contains(toolName)) {
            return true;
        }
        return toolService.findByName(toolName)
                .map(t -> Boolean.TRUE.equals(t.getEnabled()))
                .orElse(true);
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
