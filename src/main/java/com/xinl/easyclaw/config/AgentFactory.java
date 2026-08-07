package com.xinl.easyclaw.config;

import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.tools.CodeGenerationTools;
import com.xinl.easyclaw.tools.FileOperationTools;
import com.xinl.easyclaw.tools.WebSearchTools;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工厂
 * <p>
 * 创建 Toolkit 和配置，以及系统提示词。
 * Agent 实例由 WorkspaceManager 管理（每个 Workspace 一个独立的 HarnessAgent），
 * 每个 Workspace 的 Toolkit 包含：内置工具（文件/代码/搜索）+ 已连接的 MCP 工具。
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final AgentScopeProperties props;
    private final ModelRegistryService modelRegistryService;
    private final FileOperationTools fileTools;
    private final WebSearchTools searchTools;
    private final CodeGenerationTools codeTools;
    private final McpConnectionService mcpConnectionService;
    private final ToolRegistryService toolRegistryService;

    public AgentFactory(AgentScopeProperties props,
                        ModelRegistryService modelRegistryService,
                        FileOperationTools fileTools,
                        WebSearchTools searchTools,
                        CodeGenerationTools codeTools,
                        McpConnectionService mcpConnectionService,
                        ToolRegistryService toolRegistryService) {
        this.props = props;
        this.modelRegistryService = modelRegistryService;
        this.fileTools = fileTools;
        this.searchTools = searchTools;
        this.codeTools = codeTools;
        this.mcpConnectionService = mcpConnectionService;
        this.toolRegistryService = toolRegistryService;
    }

    /**
     * 当前激活的模型 ID，如 "deepseek:deepseek-chat"
     */
    public String getModelId() {
        return modelRegistryService.resolveModelId();
    }

    /**
     * 按模型 ID 解析 Model（角色/子智能体配置的模型），未注册或无效时回退全局默认模型。
     */
    public io.agentscope.core.model.Model resolveModel(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            try {
                if (io.agentscope.core.model.ModelRegistry.canResolve(modelId)) {
                    return io.agentscope.core.model.ModelRegistry.resolve(modelId);
                }
                log.warn("模型 {} 未注册，回退全局默认 {}", modelId, getModelId());
            } catch (Exception e) {
                log.warn("解析模型 {} 失败，回退全局默认: {}", modelId, e.getMessage());
            }
        }
        return io.agentscope.core.model.ModelRegistry.resolve(getModelId());
    }

    /**
     * 创建 Workspace 使用的完整 Toolkit：
     * 内置工具（文件/代码/搜索，按工具管理页启用状态过滤）+ 已连接的 MCP 服务工具
     */
    public Toolkit createWorkspaceToolkit() {
        Toolkit toolkit = new Toolkit();

        Toolkit.ToolRegistration registration = toolkit.registration();
        registration.tool(fileTools);
        registration.tool(searchTools);
        registration.tool(codeTools);
        // 按工具管理页的启用状态过滤（disableTools 按 @Tool 名称）
        List<String> disabled = toolRegistryService.disabledToolNames();
        if (!disabled.isEmpty()) {
            registration.disableTools(disabled);
            log.info("已禁用工具: {}", disabled);
        }
        registration.apply();

        // 注册已连接的 MCP 工具（失败降级，不阻断 Agent 创建）
        List<McpClientWrapper> mcpClients = mcpConnectionService.getConnectedWrappers();
        for (McpClientWrapper client : mcpClients) {
            try {
                toolkit.registerMcpClient(client).block();
                log.info("已注册 MCP 工具客户端");
            } catch (Exception e) {
                log.warn("注册 MCP 客户端失败: {}", e.getMessage());
            }
        }
        return toolkit;
    }

    /**
     * 创建代码专家 Toolkit
     */
    public Toolkit createCodeToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(codeTools);
        toolkit.registerTool(fileTools);
        return toolkit;
    }

    /**
     * 创建文件操作专家 Toolkit
     */
    public Toolkit createFileToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(fileTools);
        return toolkit;
    }

    /**
     * 创建搜索专家 Toolkit
     */
    public Toolkit createSearchToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(searchTools);
        return toolkit;
    }

    /**
     * 根据角色名称创建对应的 Toolkit
     */
    public Toolkit createToolkitByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return createWorkspaceToolkit();
        }

        String lower = roleName.toLowerCase();
        return switch (lower) {
            case "code", "代码", "代码专家", "code-expert" -> createCodeToolkit();
            case "file", "文件", "文件专家", "file-expert" -> createFileToolkit();
            case "search", "搜索", "搜索专家", "search-expert", "researcher" -> createSearchToolkit();
            default -> createWorkspaceToolkit();
        };
    }

    /**
     * 根据角色名称获取系统提示词
     */
    public String getSystemPromptByRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return defaultSystemPrompt();
        }

        String lower = roleName.toLowerCase();
        return switch (lower) {
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

    /**
     * 默认系统提示词
     */
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
