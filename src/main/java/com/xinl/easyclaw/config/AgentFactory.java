package com.xinl.easyclaw.config;

import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.tools.CodeGenerationTools;
import com.xinl.easyclaw.tools.FileOperationTools;
import com.xinl.easyclaw.tools.WebSearchTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ModelRegistryService modelRegistryService;
    private final FileOperationTools fileTools;
    private final WebSearchTools searchTools;
    private final CodeGenerationTools codeTools;
    private final McpConnectionService mcpConnectionService;
    private final ToolRegistryService toolRegistryService;
    private final ToolManagementService toolManagementService;

    /* TODO: migrate to Embabel - AgentScopeProperties removed, Toolkit removed */
    public AgentFactory(ModelRegistryService modelRegistryService,
                        FileOperationTools fileTools,
                        WebSearchTools searchTools,
                        CodeGenerationTools codeTools,
                        McpConnectionService mcpConnectionService,
                        ToolRegistryService toolRegistryService,
                        ToolManagementService toolManagementService) {
        this.modelRegistryService = modelRegistryService;
        this.fileTools = fileTools;
        this.searchTools = searchTools;
        this.codeTools = codeTools;
        this.mcpConnectionService = mcpConnectionService;
        this.toolRegistryService = toolRegistryService;
        this.toolManagementService = toolManagementService;
    }

    public String getModelId() {
        return modelRegistryService.resolveModelId();
    }

    public Object resolveModel(String modelId) {
        /* TODO: migrate to Embabel */
        return modelRegistryService.resolveOrBuild(modelId);
    }

    public Object createWorkspaceToolkit() {
        /* TODO: migrate to Embabel - Toolkit creation disabled */
        log.info("createWorkspaceToolkit() called (no-op pending Embabel migration)");
        return null;
    }

    public Object createCodeToolkit() {
        /* TODO: migrate to Embabel */
        return null;
    }

    public Object createFileToolkit() {
        /* TODO: migrate to Embabel */
        return null;
    }

    public Object createSearchToolkit() {
        /* TODO: migrate to Embabel */
        return null;
    }

    public Object createToolkitByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return createWorkspaceToolkit();
        }
        return switch (roleName.toLowerCase()) {
            case "code", "代码", "代码专家", "code-expert" -> createCodeToolkit();
            case "file", "文件", "文件专家", "file-expert" -> createFileToolkit();
            case "search", "搜索", "搜索专家", "search-expert", "researcher" -> createSearchToolkit();
            default -> createWorkspaceToolkit();
        };
    }

    public String getSystemPromptByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return defaultSystemPrompt();
        }
        return switch (roleName.toLowerCase()) {
            case "code", "代码", "代码专家", "code-expert" -> """
                    你是一个资深的代码专家，擅长 Java、Python、JavaScript 等主流编程语言。

                    你的专长：
                    - 编写高质量、可维护的代码
                    - 重构和优化现有代码
                    - 解释代码逻辑和设计模式
                    - 调试和修复 Bug
                    - 代码审查和最佳实践建议

                    回答原则：
                    1. 提供完整可运行的代码示例
                    2. 代码要有适当的注释
                    3. 遵循语言的最佳实践和命名规范
                    4. 优先考虑代码的可读性和可维护性
                    5. 如果用户没有指定语言，默认使用 Java
                    """;
            case "file", "文件", "文件专家", "file-expert" -> """
                    你是一个文件操作专家，擅长文件读写、目录管理、文件搜索等任务。

                    你的专长：
                    - 读取和分析文件内容
                    - 创建和写入文件
                    - 浏览和管理目录结构
                    - 按关键词搜索文件

                    安全原则：
                    1. 操作前确认路径
                    2. 不要删除重要文件
                    3. 写入前建议备份
                    4. 所有文件操作必须限制在当前 Workspace 目录内
                    """;
            case "search", "搜索", "搜索专家", "search-expert", "researcher" -> """
                    你是一个信息搜索专家，擅长网络信息检索和知识问答。

                    你的专长：
                    - 使用搜索工具查找信息
                    - 获取和分析网页内容
                    - 综合多个信息源给出全面的回答
                    - 对信息进行总结和提炼

                    回答原则：
                    1. 优先使用搜索工具获取最新信息
                    2. 标注信息来源
                    3. 区分事实和观点
                    4. 信息不足时诚实告知
                    """;
            default -> defaultSystemPrompt();
        };
    }

    public String defaultSystemPrompt() {
        return """
                你是 Easy-Claw AI 智能助手，一个专业、全能的编程助手。

                你的能力：
                - 代码专家：编写、重构、优化、解释代码
                - 文件专家：读写文件、管理目录、搜索文件（所有文件操作限制在当前工作区内）
                - 搜索专家：检索信息、获取网页内容
                - MCP 工具：调用外部 MCP 服务器提供的工具

                回答原则：
                1. 用中文回答，准确、专业、有条理
                2. 涉及代码时提供完整可运行的示例
                3. 需要工具时主动调用工具
                4. 不确定时坦诚告知，不要编造信息
                5. 文件路径使用相对工作区根目录的路径
                """;
    }
}
