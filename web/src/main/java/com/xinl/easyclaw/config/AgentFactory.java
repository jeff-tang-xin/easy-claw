package com.xinl.easyclaw.config;

import com.xinl.easyclaw.mcp.service.McpConnectionService;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import com.xinl.easyclaw.tool.service.ToolRegistryService;
import com.xinl.easyclaw.tools.CodeGenerationTools;
import com.xinl.easyclaw.tools.FileOperationTools;
import com.xinl.easyclaw.tools.SkillScriptTools;
import com.xinl.easyclaw.tools.WebSearchTools;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final SkillScriptTools skillScriptTools;
    private final McpConnectionService mcpConnectionService;
    private final ToolRegistryService toolRegistryService;
    private final ToolManagementService toolManagementService;

    public AgentFactory(AgentScopeProperties props,
                        ModelRegistryService modelRegistryService,
                        FileOperationTools fileTools,
                        WebSearchTools searchTools,
                        CodeGenerationTools codeTools,
                        SkillScriptTools skillScriptTools,
                        McpConnectionService mcpConnectionService,
                        ToolRegistryService toolRegistryService,
                        ToolManagementService toolManagementService) {
        this.props = props;
        this.modelRegistryService = modelRegistryService;
        this.fileTools = fileTools;
        this.searchTools = searchTools;
        this.codeTools = codeTools;
        this.skillScriptTools = skillScriptTools;
        this.mcpConnectionService = mcpConnectionService;
        this.toolRegistryService = toolRegistryService;
        this.toolManagementService = toolManagementService;
    }

    /**
     * 当前激活的模型 ID，如 "deepseek:deepseek-chat"
     */
    public String getModelId() {
        return modelRegistryService.resolveModelId();
    }

    /**
     * 按模型 ID 解析 Model（角色/子智能体配置的模型）。
     * <p>
     * 解析策略（按优先级）：
     * <ol>
     *   <li>ModelRegistry 已注册 → 直接返回</li>
     *   <li>未注册 → 动态构建：有 provider 前缀取对应 provider 凭证，无前缀用激活 provider</li>
     *   <li>动态构建也失败 → 回退全局默认模型</li>
     * </ol>
     */
    public io.agentscope.core.model.Model resolveModel(String modelId) {
        return modelRegistryService.resolveOrBuild(modelId);
    }

    /**
     * 按角色解析 Model：角色自带 baseUrl+apiKey 时用其独立端点，
     * 否则按 modelId 走全局 provider 配置。
     */
    public io.agentscope.core.model.Model resolveRoleModel(String modelId, String baseUrl, String apiKey) {
        return modelRegistryService.resolveWithCredentials(modelId, baseUrl, apiKey);
    }

    /**
     * 创建 Workspace 使用的完整 Toolkit：
     * 内置工具（文件/代码/搜索，按工具管理页启用状态过滤）+ 用户定义的 HTTP 工具 + 已连接的 MCP 服务工具
     */
    public Toolkit createWorkspaceToolkit() {
        return createWorkspaceToolkit(null);
    }

    /**
     * 创建 Workspace Toolkit，并按场景绑定的 MCP 服务做<b>硬隔离</b>。
     * <p>
     * 与无参版本的唯一区别：{@code allowedMcpServices} 非空时，只注册这些服务的
     * MCP 工具；其余已连接服务被跳过。内置工具（文件/代码/搜索）不受影响 ——
     * 它们由 {@code CapabilityTier} 在子智能体层面裁剪，主智能体始终保留。
     *
     * @param allowedMcpServices 允许的 MCP 服务名；<b>null/空 = 不限制</b>（注册全部，向后兼容）
     */
    public Toolkit createWorkspaceToolkit(List<String> allowedMcpServices) {
        Toolkit toolkit = new Toolkit();

        Toolkit.ToolRegistration registration = toolkit.registration();
        registration.tool(fileTools);
        registration.tool(searchTools);
        registration.tool(codeTools);
        registration.tool(skillScriptTools);
        // 按工具管理页的启用状态过滤（disableTools 按 @Tool 名称）
        List<String> disabled = toolRegistryService.disabledToolNames();
        if (!disabled.isEmpty()) {
            registration.disableTools(disabled);
            log.info("已禁用工具: {}", disabled);
        }
        registration.apply();

        // 注册 MCP HTTP_TOOL 桥接（REST API 包装成 AgentTool）
        List<AgentTool> httpTools = mcpConnectionService.getHttpTools();
        for (AgentTool tool : httpTools) {
            toolkit.registerAgentTool(tool);
            log.info("已注册 HTTP_TOOL 桥接: {}", tool.getName());
        }

        // 注册已连接的外部 MCP 工具（STDIO / STREAMABLE_HTTP / SSE）
        Set<String> mcpAllowlist = normalizedServiceNames(allowedMcpServices);
        Map<Long, McpClientWrapper> mcpClients = mcpConnectionService.getConnectedWrappers();
        mcpClients.forEach((serviceId, client) -> {
            try {
                if (!mcpAllowlist.isEmpty() && !isMcpServiceAllowed(serviceId, mcpAllowlist)) {
                    log.info("场景未绑定该 MCP 服务，跳过注册 (serviceId={})", serviceId);
                    return;
                }
                List<String> enableTools = mcpConnectionService.getEnabledTools(serviceId);
                registerMcpWithFilters(toolkit, client, enableTools);
                if (enableTools.isEmpty()) {
                    log.info("已注册外部 MCP 工具客户端 (serviceId={}, 全部工具)", serviceId);
                } else {
                    log.info("已注册外部 MCP 工具客户端 (serviceId={}, 启用 {} 个工具)", serviceId, enableTools.size());
                }
            } catch (Exception e) {
                log.warn("注册外部 MCP 客户端失败 (serviceId={}): {}", serviceId, e.getMessage());
            }
        });
        return toolkit;
    }

    /** 规范化场景绑定的 MCP 服务名（去空、trim、小写），空集表示不限制 */
    private Set<String> normalizedServiceNames(List<String> serviceNames) {
        Set<String> result = new HashSet<>();
        if (serviceNames == null) {
            return result;
        }
        for (String name : serviceNames) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return result;
    }

    /**
     * 判断某个 MCP 服务是否在场景白名单内。
     * <p><b>fail-closed</b>：查不到服务记录时返回 false —— 硬隔离场景下
     * 「无法确认身份」必须按拒绝处理，不能放行。
     */
    private boolean isMcpServiceAllowed(Long serviceId, Set<String> allowlist) {
        try {
            return mcpConnectionService.findAll().stream()
                    .filter(e -> serviceId.equals(e.getId()))
                    .findFirst()
                    .map(e -> e.getName() != null
                            && allowlist.contains(e.getName().trim().toLowerCase(java.util.Locale.ROOT)))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("校验 MCP 服务白名单失败 (serviceId={})，按拒绝处理: {}", serviceId, e.getMessage());
            return false;
        }
    }

    /**
     * 创建代码专家 Toolkit
     */
    public Toolkit createCodeToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(codeTools);
        toolkit.registerTool(skillScriptTools);
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

                ━━ 工具使用协议 ━━

                1. 先理解再动手：收到请求后先判断是否需要工具。纯知识性问题直接回答，不浪费工具调用。

                2. 选对工具：
                   - 读取文件内容 → read_file（支持 offset/limit 分页）
                   - 写入/创建文件 → write_file；局部修改 → edit_file
                   - 按内容搜索 → grep_files；按文件名搜索 → search_files 或 glob_files
                   - 浏览目录结构 → list_directory
                   - 执行 Shell 命令 → execute
                   - 网络搜索 → web_search；抓取已知 URL → fetch_webpage
                   - 每个工具的 description 里有「何时用 / 不要用于」指引，遵守它。

                3. 大文件必须分段写：write_file 单次写入有长度上限，内容过长会被截断或写入失败。
                   - 预估产出超过约 300 行 / 15KB 时，先用 write_file 写入第一段（如骨架、前几个部分），
                     再用 edit_file 逐段把剩余内容追加进去，每段控制在安全体积内。
                   - 禁止把超长内容塞进一次 write_file 调用。
                   - 写完后用 read_file 抽查确认内容完整（尤其是结尾），发现被截断就用 edit_file 补齐。

                4. 并行优先：多个独立工具调用（如同时读两个文件）尽量放在同一轮发起，减少往返。

                5. 确认类工具：需要用户确认的工具会暂停流程，等待用户批准后继续，不要在等待期间重复发起相同调用。

                6. 报错处理：
                   - 工具返回 ❌ 或 ⚠️ 开头表示失败。读取错误信息，修正参数后重试一次。
                   - 同一工具连续失败 2 次后停止重试，向用户说明失败原因并询问如何处理。
                   - 常见原因：路径不存在（先用 list_directory 确认）、权限不足（说明并询问）、参数格式错误（检查后修正）。

                7. 长结果：工具返回内容较长时，提取关键信息用于回答，不要原样粘贴超长输出。

                8. 引用来源：使用 web_search / fetch_webpage 获取的信息，在回答中标注来源链接。

                ━━ 任务闭环协议（最重要）━━

                1. 任务未完成就继续干：收到工具返回结果后，必须基于结果判断下一步并继续执行，直到任务完成才能结束回合。禁止调完一个工具就停下来说"我保持等待/请告诉我下一步"。

                2. 你是执行者，不是传话筒：用户说"编译一下""跑个 build"，意思是你要把整件事做完（执行命令 → 看输出 → 修复问题/报告结果），而不是只执行一步就回头询问。

                3. 回合结束时必须有交付：每次回合结束，你的最后一条消息要么是任务完成的结果总结，要么是明确说明"卡在哪里、需要用户决定什么"。绝不允许以"等待下一步指令"作为回合的结尾。

                4. 只有三种情况可以中途问用户：
                   - 缺少关键信息，不问就无法继续（说明缺什么）
                   - 高风险操作需要授权（删除、覆盖、对外发布等）
                   - 需求本身有歧义，两种理解会导致完全不同的结果
                   除此以外一律自己判断、自己继续。

                5. 多步任务先列计划：复杂任务先在心里拆解步骤（必要时用文字简述），然后按顺序连续执行，中间步骤不需要用户确认。

                ━━ 回答原则 ━━

                1. 用中文回答，准确、专业、有条理
                2. 涉及代码时提供完整可运行的示例
                3. 需要工具时主动调用工具，调用后基于工具返回的客观结果回答
                4. 不确定时坦诚告知，不要编造信息
                5. 文件路径使用相对工作区根目录的路径
                """;
    }

    private void registerMcpWithFilters(Toolkit toolkit, McpClientWrapper client, List<String> enableTools) {
        if (enableTools == null || enableTools.isEmpty()) {
            toolkit.registerMcpClient(client).block();
            return;
        }
        try {
            java.lang.reflect.Field f = Toolkit.class.getDeclaredField("mcpClientManager");
            f.setAccessible(true);
            Object mcm = f.get(toolkit);
            java.lang.reflect.Method m = mcm.getClass().getDeclaredMethod(
                    "registerMcpClient", McpClientWrapper.class, List.class);
            m.setAccessible(true);
            ((reactor.core.publisher.Mono<?>) m.invoke(mcm, client, enableTools)).block();
        } catch (Exception e) {
            log.warn("反射注册 MCP 带 enableTools 失败，退回全量注册: {}", e.getMessage());
            toolkit.registerMcpClient(client).block();
        }
    }
}
