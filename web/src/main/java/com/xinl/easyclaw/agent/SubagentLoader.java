package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.role.RolePromptComposer;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 子 Agent 声明加载器
 * <p>
 * 从 Workspace 的 {@code subagents} 目录加载子 Agent 声明文件
 * （{@code <workspace>/subagents/<agent_id>.md}，文件名即 agent_id），
 * 解析 YAML frontmatter（description/model/steps/tools/skills）与正文（系统提示词），
 * 构建 {@link SubagentDeclaration} 注册到主控 HarnessAgent。
 * <p>
 * 所有子 Agent 与主控共享同一个 Workspace，文件操作同样受沙箱限制。
 */
@Component
public class SubagentLoader {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoader.class);

    /**
     * 共享黑板的工具名。与 {@code BlackboardTools} 上 {@code @Tool} 注解的名字必须一致 ——
     * 改了那边一定要同步改这里，否则白名单补齐会失效（且不报错，只是子 Agent 又调不到黑板）。
     */
    private static final List<String> BLACKBOARD_TOOL_NAMES =
            List.of("blackboard_append", "blackboard_read");

    /**
     * {@code .md} 声明里 {@code tools:} 的<b>历史别名 → 真实注册名</b>映射。
     * <p>
     * <b>为什么需要</b>：harness 的 {@code allowlistedInheritedToolkit}
     * （{@code HarnessAgentBuilderSupport:569}）按工具名<b>严格相等</b>裁剪 ——
     * 凡不在白名单内的一律 {@code removeTool}。而内置 {@code .md} 写的是
     * {@code shell / search / grep / glob} 这类简写，真实 {@code @Tool} 注册名是
     * {@code execute / search_files / grep_files / glob_files}。两边对不上的后果是
     * <b>静默失能</b>：声明 7 个工具实际只活下来 3 个，既不报错也无日志，
     * 表现为子 Agent「莫名不会用 shell」。
     * <p>
     * <b>为什么放在代码里而不是只改 .md</b>：存量用户机器上的
     * {@code ~/.easyClaw/subagents/*.md} 不会被播种覆盖（理由同 {@link #BLACKBOARD_GUIDE}），
     * 光改 resources 种子对他们完全无效。此处归一化与磁盘文件内容无关，一定生效；
     * resources 里的 {@code .md} 同步改为真名，则是让声明自身可读、新用户不再产生别名。
     * <p>
     * <b>{@code search} 为何映射到 {@code search_files} 而非 {@code web_search}</b>：
     * {@code researcher.md} 的 tools 同时列了 {@code web_search} 和 {@code search}，
     * 若两者同义则该声明自我重复 —— 故 {@code search} 只能指按名字找文件。
     * <p>
     * 映射刻意只收录<b>确凿的</b>历史别名。无法确定语义的未知名字一律原样保留并告警，
     * 猜错映射比不映射更危险（会悄悄给出一个作者没打算给的工具）。
     */
    private static final Map<String, String> TOOL_NAME_ALIASES = Map.ofEntries(
            Map.entry("shell", "execute"),
            Map.entry("bash", "execute"),
            Map.entry("search", "search_files"),
            Map.entry("grep", "grep_files"),
            Map.entry("glob", "glob_files"),
            Map.entry("list", "list_files"),
            Map.entry("ls", "list_files"),
            Map.entry("read", "read_file"),
            Map.entry("write", "write_file"),
            Map.entry("edit", "edit_file"));

    /**
     * 全部真实工具注册名，用于校验 {@code tools:} 里的名字是否真的存在。
     * <p>
     * 与 {@code ToolRegistryService.GROUPS} 同源同名（框架内置 + 自定义 @Tool），
     * 但<b>刻意不引用</b>那边的常量：{@code GROUPS} 是给前端做分组展示的，
     * 语义是「这个工具属于哪一类」，被当成「有效名字表」用会让两个用途互相绑死 ——
     * 那边为展示需要增删一项，这边的校验就会跟着变。此处只做告警不做裁剪，
     * 漏收一两个名字最多少打一条日志，代价可控。
     */
    private static final Set<String> KNOWN_TOOL_NAMES = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files",
            "list_directory", "search_files",
            "analyze_code", "format_code", "diff_code", "inspect_data",
            "run_python", "run_skill_script",
            "web_search", "web_fetch", "fetch_webpage",
            "memory_search", "memory_get", "memory_save",
            "session_search", "session_list", "session_history",
            "agent_spawn", "agent_send", "agent_list", "agent_generate",
            "task_output", "task_cancel", "task_list", "wait_async_results",
            "execute",
            "blackboard_append", "blackboard_read");

    /**
     * 注入到每个子 Agent system prompt 尾部的共享黑板协作段。
     * <p>
     * <b>为什么必须程序化注入，而不是写进 6 个 {@code .md} 种子里</b>：
     * 用户机器上已存在旧版 {@code .md}（{@code ~/.easyClaw/subagents/}），
     * 两套播种机制都会跳过已存在的文件（{@code AiAssistantApplication.seedBundledSubagents}
     * 判 {@code Files.exists} 直接 continue；{@code SystemDataSeeder.shouldOverwrite}
     * 在无 seedVersion 时视为用户自定义而保守不动）。改种子对存量用户完全无效，
     * 而这恰恰是最需要修的场景。放在这里则与磁盘文件内容无关，一定生效。
     * <p>
     * 内容上刻意只讲「什么时候写、写什么、别写什么」，不讲参数细节 ——
     * 参数说明在工具自己的 description 里，重复一遍只会占上下文且容易与实现漂移。
     */
    private static final String BLACKBOARD_GUIDE = """

            ## 🤝 共享黑板（与同伴协作的唯一通道）
            你正在一个多智能体任务中工作。**你看不到其他子 Agent 的对话，他们也看不到你的**。
            共享黑板是你们之间唯一的信息通道，用 `blackboard_read` 读、`blackboard_append` 写。

            1. **开工前先读一次**：`blackboard_read` 看同伴已登记的结论与风险。
               别人已经查清的事不要重查，别人踩过的坑不要再踩，与已有结论冲突时先说明理由。
            2. **得出结论就立刻登记**，不要攒到最后一起写。同伴可能正卡在等你这个结论 ——
               你憋到任务结束才写，他就只能空等或自己重做一遍。
            3. **只登记这四类**：确定的事实（finding）、风险与坑（risk）、结论与决定（conclusion）、
               必要的补充说明（note）。
            4. **不要登记**：过程日志、中间草稿、大段代码或原文粘贴、以及给用户的最终答复
               （最终答复写在你的回复里，那才是会被汇总的地方）。
            5. **黑板只能追加，无法删改**。对他人条目有异议时追加一条说明理由，不要试图覆盖。
            6. 一句话写清「是什么 + 对别人意味着什么」。只有影响别人做法的信息才值得占黑板。
            """;

    private final RoleManagementService roleService;
    private final AgentScopeProperties properties;

    public SubagentLoader(RoleManagementService roleService, AgentScopeProperties properties) {
        this.roleService = roleService;
        this.properties = properties;
    }

    /**
     * 合并加载全局 + Workspace 两级子 Agent 声明（无场景绑定 = 不限制）。
     * 规则：workspace 级覆盖 global 级（同名时后者覆盖前者）。
     */
    public List<SubagentDeclaration> loadMerged(Path globalDir, Path workspaceDir) {
        return loadMerged(globalDir, workspaceDir, ScenarioBinding.EMPTY);
    }

    /**
     * 合并加载并施加场景绑定的<b>子 Agent 硬隔离</b>。
     * <p>
     * 隔离只作用于 skill 白名单，<b>不碰 tools</b>：MCP 硬隔离已在父 toolkit
     * 注册阶段完成（见 {@code AgentFactory.createWorkspaceToolkit(List)}），
     * 子 Agent 继承的副本天然看不到未绑定的 MCP 工具。若在此再塞一份工具白名单，
     * harness 的 {@code allowlistedInheritedToolkit} 会把不在名单里的
     * {@code read_file}/{@code execute} 等基础工具一并删光。
     *
     * @param binding 场景绑定；{@link ScenarioBinding#EMPTY} 表示不限制
     */
    public List<SubagentDeclaration> loadMerged(Path globalDir, Path workspaceDir,
                                                ScenarioBinding binding) {
        ScenarioBinding effective = binding == null ? ScenarioBinding.EMPTY : binding;
        Map<String, SubagentDeclaration> merged = new LinkedHashMap<>();
        for (SubagentDeclaration decl : loadFromDirectory(globalDir, effective)) {
            merged.put(decl.getName(), decl);
        }
        for (SubagentDeclaration decl : loadFromDirectory(workspaceDir, effective)) {
            if (merged.containsKey(decl.getName())) {
                log.info("子 Agent [{}] 被 workspace 级声明覆盖（workspace 优先）", decl.getName());
            }
            merged.put(decl.getName(), decl);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 扫描目录下的子 Agent 声明文件，返回声明列表（文件不存在/为空时返回空列表）
     */
    public List<SubagentDeclaration> loadFromDirectory(Path subagentsDir) {
        return loadFromDirectory(subagentsDir, ScenarioBinding.EMPTY);
    }

    /** 扫描目录并施加场景 skill 隔离 */
    public List<SubagentDeclaration> loadFromDirectory(Path subagentsDir, ScenarioBinding binding) {
        List<SubagentDeclaration> declarations = new ArrayList<>();
        if (subagentsDir == null || !Files.isDirectory(subagentsDir)) {
            return declarations;
        }

        try (Stream<Path> stream = Files.list(subagentsDir)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    SubagentDeclaration decl = parse(file, binding);
                    if (decl != null) {
                        declarations.add(decl);
                    }
                } catch (Exception e) {
                    log.warn("解析子 Agent 声明失败: {} - {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取子 Agent 目录失败: {} - {}", subagentsDir, e.getMessage());
        }

        if (!declarations.isEmpty()) {
            log.info("已加载 {} 个子 Agent: {}", declarations.size(),
                    declarations.stream().map(d -> d.getName()).toList());
        }
        return declarations;
    }

    private SubagentDeclaration parse(Path file, ScenarioBinding binding) throws IOException {
        String content = Files.readString(file);
        String agentId = file.getFileName().toString().replaceAll("\\.md$", "");

        // 解析 YAML frontmatter（--- 包裹）
        String description = "";
        String model = null;
        String roleName = null;
        // 未在 frontmatter 显式指定 steps 时用配置值（默认 30），而非框架的 10。
        // 框架默认 10 步对「读若干文件 + 分析 + 汇总」这类任务明显不够，
        // 耗尽后会触发 ExceedMaxItersEvent 强行结束 → 回复被截断。
        // team 模式另有更高下限，见 effectiveStepFloor。
        int steps = effectiveStepFloor(binding);
        List<String> tools = null;
        List<String> skills = null;
        // 会话复用：默认开启，让同一子 Agent 被返工重派时不必从零重读代码。
        // 框架侧 key 由 deterministicHash(parentSessionId, agentId, label) 推导
        // （AgentSpawnTool:371），与非复用分支的 UUID 随机 key 相对。
        // 声明可用 persistSession: false 显式关闭。
        boolean persistSession = true;

        String body = content;
        if (content.trim().startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                body = content.substring(end + 3);
                for (String line : frontmatter.split("\n")) {
                    String l = line.trim();
                    if (l.isBlank() || l.startsWith("#")) {
                        continue;
                    }
                    int idx = l.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = l.substring(0, idx).trim();
                    String value = l.substring(idx + 1).trim()
                            .replaceAll("^[\"']|[\"']$", "");
                    switch (key) {
                        case "description" -> description = value;
                        case "model" -> model = value;
                        case "role" -> roleName = value;
                        case "steps" -> {
                            try {
                                steps = Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                                // 保留默认
                            }
                        }
                        case "tools" -> tools = parseNameList(value);
                        case "skills" -> skills = parseNameList(value);
                        case "persistSession", "persist-session" ->
                                persistSession = Boolean.parseBoolean(value);
                        default -> {
                            // 忽略未知字段
                        }
                    }
                }
            }
        }

        String prompt = body.trim();
        if (prompt.isEmpty()) {
            prompt = "You are a helpful subagent named " + agentId + ".";
        }

        int configuredSteps = effectiveStepFloor(binding);
        // 显式写了但明显偏低的老声明（内置的 10/12）同样会截断，统一抬到配置下限。
        // 高于配置值的显式设置予以尊重（作者刻意放宽）。
        if (steps < configuredSteps) {
            log.info("子 Agent [{}] steps={} 低于当前下限 {}，已抬升（避免回复被截断）",
                    agentId, steps, configuredSteps);
            steps = configuredSteps;
        }

        // 绑定角色：一次查库同时拿人格与模型（编排单位是角色，.md 只是执行外壳）
        AgentRoleEntity boundRole = null;
        if (roleName != null && !roleName.isBlank()) {
            try {
                boundRole = roleService.findByName(roleName.trim()).orElse(null);
                if (boundRole == null) {
                    log.warn("子 Agent [{}] 声明的角色 [{}] 不存在，按无角色处理", agentId, roleName.trim());
                }
            } catch (Exception e) {
                log.debug("读取角色 {} 失败: {}", roleName, e.getMessage());
            }
        }

        // 人格：角色的 role/goal/backstory 置于声明正文之前。
        // 角色回答「你是什么」（身份），.md 正文回答「你怎么干活」（专项工作方法），
        // 二者正交故叠加而非互斥；角色在前使身份先于方法确立。
        String persona = RolePromptComposer.compose(boundRole);
        if (persona != null) {
            prompt = persona + "\n\n" + prompt;
        }

        // 共享黑板协作段：置于最后，作为跨子 Agent 的协作协议补充在专项方法之后。
        // 注入条件刻意宽于 team 模式 —— 单智能体场景下主 Agent 也可能派子 Agent，
        // 只要有两个子 Agent 就存在互不可见问题，黑板一律可用（工具是全局注册的）。
        prompt = prompt + "\n" + BLACKBOARD_GUIDE;

        SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                .name(agentId)
                .description(description.isBlank() ? "子 Agent: " + agentId : description)
                .inlineAgentsBody(prompt)
                .steps(steps)
                .persistSession(persistSession);
        // 模型：frontmatter 显式指定优先；否则取关联角色的模型配置（团队模式按角色模型运行）。
        // 注：角色的 baseUrl/apiKey 无法经 SubagentDeclaration 传递（声明层只有 model 字符串），
        // 故角色私有端点在子 Agent 上不生效 —— 现有角色 model 均留空跟随全局，暂无影响。
        if ((model == null || model.isBlank()) && boundRole != null
                && boundRole.getModel() != null && !boundRole.getModel().isBlank()) {
            model = boundRole.getModel();
        }
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        List<String> effectiveTools = withBlackboardTools(
                restrictTools(agentId, normalizeToolNames(agentId, tools), binding));
        if (effectiveTools != null) {
            builder.tools(effectiveTools);
        }
        List<String> effectiveSkills = restrictSkills(agentId, skills, binding);
        if (effectiveSkills != null) {
            builder.skills(effectiveSkills);
        }
        return builder.build();
    }

    /**
     * 子 Agent 的迭代步数下限。
     * <p>
     * 非 team 模式沿用 {@code agent.subagentSteps}（默认 30）：子 Agent 只是被喊来
     * 干一件小事，30 步足够，且能防止跑飞烧 token。
     * <p>
     * <b>team 模式抬到与主 Agent 相同的 {@code agent.maxIters}（默认 50）</b>：
     * 此时子 Agent 承担的是「实现一个模块」「评审一批文件」这类完整子任务，
     * 步数不足会在 {@code ExceedMaxItersEvent} 处被硬截断 —— 表现为交付半成品
     * 而非报错，编排者还会把这份残缺产出当成成品汇总，故障非常隐蔽。
     * <p>
     * 若配置里 maxIters 反而小于 subagentSteps，取较大者，避免「开了 team 反而更短」。
     */
    private int effectiveStepFloor(ScenarioBinding binding) {
        int base = properties.getAgent().getSubagentSteps();
        if (binding == null || !binding.isTeamMode()) {
            return base;
        }
        return Math.max(base, properties.getAgent().getMaxIters());
    }

    /**
     * 把共享黑板工具补进白名单。
     * <p>
     * <b>为什么必须补</b>：黑板协作段（见 {@link #BLACKBOARD_GUIDE}）会指示子 Agent
     * 登记结论，但内置 {@code .md} 声明的 {@code tools:} 白名单里没有黑板工具，
     * harness 的 {@code allowlistedInheritedToolkit} 会把它裁掉 ——
     * 结果是提示词让模型调一个不存在的工具，模型反复重试直到步数耗尽。
     * 提示词与工具箱必须同时到位，缺一个都比两个都没有更糟。
     *
     * @param effective 现有白名单；{@code null} 表示不限制，此时无需补（继承父 toolkit）
     */
    /**
     * 把 {@code tools:} 里的历史别名换成真实注册名，并对不认识的名字告警。
     * <p>
     * 必须在 {@link #restrictTools} <b>之前</b>执行：档位白名单存的是真名，
     * 若拿别名去取交集，{@code shell} 与 {@code execute} 不相等 →
     * 声明会被误判为「超出档位范围」，反而触发「全部超范围」的兜底分支。
     * <p>
     * 未知名字<b>保留而不剔除</b>：可能是 MCP 动态工具或本方法尚未收录的新工具，
     * 剔除会造成真实的能力损失；留着最多是白名单里多一个匹配不到的名字（无副作用）。
     * 但一定要告警 —— 拼错工具名是静默失能，没有日志根本查不出来。
     *
     * @param declared 声明里的原始名字；{@code null} 表示未声明（不限制），原样返回
     */
    private List<String> normalizeToolNames(String agentId, List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return declared;
        }
        Set<String> normalized = new LinkedHashSet<>();
        List<String> renamed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : declared) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            String real = TOOL_NAME_ALIASES.get(name.toLowerCase());
            if (real != null) {
                renamed.add(name + "->" + real);
                normalized.add(real);
                continue;
            }
            if (!KNOWN_TOOL_NAMES.contains(name)) {
                unknown.add(name);
            }
            normalized.add(name);
        }
        if (!renamed.isEmpty()) {
            log.info("子 Agent [{}] tools 别名已归一化: {}", agentId, renamed);
        }
        if (!unknown.isEmpty()) {
            log.warn("子 Agent [{}] tools 中有 {} 个名字不在已知工具清单内: {} —— "
                    + "若非 MCP 工具则可能是拼写错误，该工具会被 harness 静默裁掉",
                    agentId, unknown.size(), unknown);
        }
        return List.copyOf(normalized);
    }

    private List<String> withBlackboardTools(List<String> effective) {        if (effective == null) {
            return null;
        }
        Set<String> merged = new LinkedHashSet<>(effective);
        merged.addAll(BLACKBOARD_TOOL_NAMES);
        return List.copyOf(merged);
    }

    /**
     * 计算子 Agent 的<b>有效工具白名单</b>：档位基础工具 ∪ 场景绑定的 MCP 工具，
     * 再与声明自身的 tools 取交集。
     * <p>
     * <b>为什么必须并上档位工具</b>：harness 的 {@code allowlistedInheritedToolkit} 一刀切裁剪，
     * 不区分工具来源。若只把 MCP 工具名写进白名单，子 Agent 会连 {@code read_file} 都失去。
     * <p>
     * <b>为什么要求档位显式配置</b>：档位默认值是 STANDARD，若「无 tier 配置」也走裁剪，
     * 那么所有既有场景的子 Agent 都会突然失去 {@code execute} 与子 Agent 调度能力 ——
     * 这是静默的能力回退。因此仅当场景<b>显式</b>写了 capabilityTier 或绑定了 MCP 时才裁剪。
     *
     * @return 有效工具列表；{@code null} 表示不限制（继承父 toolkit 全部工具）
     */
    private List<String> restrictTools(String agentId, List<String> declared,
                                       ScenarioBinding binding) {
        if (binding == null || !binding.hasToolBinding()) {
            return declared;
        }
        Set<String> allowed = new LinkedHashSet<>(binding.tier().toolNames());
        allowed.addAll(binding.mcpTools());
        if (allowed.isEmpty()) {
            // NONE 档位且无 MCP 绑定：给空名单会被 harness 当作「不限制」原样放行，
            // 与用户意图相反。此处保留声明值并告警，避免产生「配了却没生效」的错觉。
            log.warn("子 Agent [{}] 场景档位为 NONE 且未绑定 MCP，工具白名单为空，"
                    + "已按不裁剪处理（如需真正禁用请改用 tools 声明）", agentId);
            return declared;
        }
        if (declared == null || declared.isEmpty()) {
            log.info("子 Agent [{}] 未声明 tools，采用场景档位 {} 白名单（{} 个工具）",
                    agentId, binding.tier(), allowed.size());
            return List.copyOf(allowed);
        }
        List<String> intersection = new ArrayList<>();
        for (String name : declared) {
            if (containsIgnoreCase(allowed, name)) {
                intersection.add(name);
            }
        }
        if (intersection.isEmpty()) {
            log.warn("子 Agent [{}] 声明的 tools {} 全部超出场景档位 {} 范围，"
                    + "按档位白名单处理（避免该子 Agent 完全无工具可用）",
                    agentId, declared, binding.tier());
            return List.copyOf(allowed);
        }
        if (intersection.size() < declared.size()) {
            log.info("子 Agent [{}] tools 被场景档位收窄: {} -> {}", agentId, declared, intersection);
        }
        return intersection;
    }

    /**
     * 计算子 Agent 的<b>有效 skill 白名单</b>：声明自身的 skills 与场景绑定取<b>交集</b>。
     * <p>
     * 取交集而非覆盖，是因为两个限制都有存在理由，谁都不该被绕过：
     * 声明里的 skills 是作者对该子 Agent 职责的收窄，场景绑定是运行时的能力边界。
     * <p>
     * 边界情形：
     * <ul>
     *   <li>场景无 skill 绑定 → 原样返回声明值（可能为 null = 不限制）</li>
     *   <li>声明未写 skills → 直接采用场景绑定</li>
     *   <li>交集为空 → 回退为场景绑定并记 warn。给空集会让 harness 的
     *       {@code SkillFilter.only(空)} 把该子 Agent 的 skill 全禁掉，
     *       「配置写错」不该升级成「子 Agent 不可用」</li>
     * </ul>
     *
     * @return 有效 skill 列表；{@code null} 表示不限制
     */
    private List<String> restrictSkills(String agentId, List<String> declared,
                                        ScenarioBinding binding) {
        if (binding == null || !binding.hasSkillBinding()) {
            return declared;
        }
        List<String> bound = binding.skills();
        if (declared == null || declared.isEmpty()) {
            log.info("子 Agent [{}] 未声明 skills，采用场景绑定: {}", agentId, bound);
            return bound;
        }
        List<String> intersection = new ArrayList<>();
        for (String name : declared) {
            if (containsIgnoreCase(bound, name)) {
                intersection.add(name);
            }
        }
        if (intersection.isEmpty()) {
            log.warn("子 Agent [{}] 声明的 skills {} 与场景绑定 {} 无交集，"
                    + "按场景绑定处理（避免该子 Agent 完全失去 skill）", agentId, declared, bound);
            return bound;
        }
        if (intersection.size() < declared.size()) {
            log.info("子 Agent [{}] skills 被场景收窄: {} -> {}", agentId, declared, intersection);
        }
        return intersection;
    }

    private boolean containsIgnoreCase(Collection<String> pool, String target) {
        for (String candidate : pool) {
            if (candidate.equalsIgnoreCase(target.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 frontmatter 中的名字列表，兼容 YAML 行内数组与裸逗号分隔两种写法：
     * <pre>
     * tools:  [read_file, grep_files]
     * skills: "clean-code", 'code-refactor'
     * </pre>
     * 与 harness {@code AgentSpecLoader.parseToolNames} 的行为保持一致。
     *
     * @return 非空名字列表；全为空白时返回 {@code null} 表示「未声明 = 不限制」
     */
    private List<String> parseNameList(String value) {
        List<String> list = new ArrayList<>();
        for (String t : value.split("[,\\[\\]\"']")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.isEmpty() ? null : list;
    }
}
