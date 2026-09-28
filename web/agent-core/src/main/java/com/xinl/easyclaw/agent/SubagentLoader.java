package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.base.agent.AgentContext;
import com.xinl.easyclaw.base.agent.EasyClawAgent;
import com.xinl.easyclaw.base.agent.ModelPreference;
import com.xinl.easyclaw.base.agent.SkillPolicy;
import com.xinl.easyclaw.base.agent.ToolPolicy;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 从 SPI 注册表（{@link AgentRegistry}）取得内置智能体，构建
 * {@link SubagentDeclaration} 注册到主控 HarnessAgent。<b>SPI 是唯一来源</b>——
 * 历史上的 {@code <workspace>/subagents/*.md} 扫描已整体下线，原因见
 * {@link #loadMerged(ScenarioBinding)} 的说明（BOM 导致 frontmatter 静默失效，
 * 且失效方向是「放权」而非「收权」）。
 * <p>
 * 所有子 Agent 与主控共享同一个 Workspace，文件操作同样受沙箱限制。
 */
@Component
public class SubagentLoader {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoader.class);

    /**
     * 子 Agent 迭代步数的<b>绝对下限</b>，任何配置与声明都不得低于此值。
     * <p>
     * 30 步是「读若干文件 + 分析 + 产出结论」的经验底线。低于它时，
     * {@code ReActAgent} 在 {@code iter >= maxIters} 处进入 summarizing 分支：
     * 未完成的工具调用被塞进错误块，并追加提示让模型「就现有信息总结」——
     * 于是子 Agent 会返回一段<b>读起来像正常完成</b>的结论，而 {@code ExceedMaxItersEvent}
     * 只走事件流、不进 {@code agent_spawn} 的返回字符串。编排者因此无从分辨半成品，
     * 会把残缺产出当成品汇总。这类故障不报错、不留痕，故设为不可被配置突破的硬下限。
     */
    private static final int ABSOLUTE_STEP_FLOOR = 30;

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

            ## 🤝 共享黑板（唯一能幸存的产出通道）
            你正在一个多智能体任务中工作。**你看不到其他子 Agent 的对话，他们也看不到你的**。
            共享黑板用 `blackboard_read` 读、`blackboard_append` 写。

            ⚠️ **先记住这条**：你随时可能被**强制中断**——步数耗尽，或任务超时被直接掐断
            （后者**没有任何最后陈述的机会**，你的回复根本不会产生）。一旦发生，
            **你这次做的所有工作都会蒸发，主控只能看到你写进黑板的东西**。
            所以写黑板首先是为你自己止损，其次才是给同伴看：
            **即使你是本次唯一的执行体，也必须边做边写**——读者是主控，不只是同伴。

            1. **开工前先读一次**：`blackboard_read` 看同伴已登记的结论与风险。
               别人已经查清的事不要重查，别人踩过的坑不要再踩，与已有结论冲突时先说明理由。
            2. **完成一个可独立交付的小块就立刻写一条**，不要攒到最后一起写。
               典型落盘时机：查清了关键事实 / 定下了实现方案 / 改完一个文件 / 发现一个坑。
               憋到任务结束才写 = 赌自己不会被中断，而这个赌注是你全部的工作量。
            3. **只登记这四类**：确定的事实（finding）、风险与坑（risk）、结论与决定（conclusion）、
               必要的补充说明（note）。
            4. **不要登记**：过程日志、中间草稿、大段代码或原文粘贴、以及给用户的最终答复
               （最终答复写在你的回复里，那才是会被汇总的地方）。
            5. **黑板只能追加，无法删改**。对他人条目有异议时追加一条说明理由，不要试图覆盖。
            6. 一句话写清「是什么 + 对别人意味着什么」。只有影响别人做法的信息才值得占黑板。
            """;

    /**
     * 注入到每个子 Agent system prompt 尾部的<b>交付纪律</b>段。
     * <p>
     * <b>为什么与 {@link #BLACKBOARD_GUIDE} 分开</b>：黑板段解决「产出会不会丢」，
     * 本段解决「产出可不可信」——两者正交。黑板段已充分交代中断风险，此处不再重复，
     * 只讲汇报形态。
     * <p>
     * <b>为什么需要</b>：主控侧（编排行为规范 {@code OrchestrationBehavior}）被明确要求
     * 「要证据不要结论」「只说已修复而拿不出证据的一律视为未完成」，但子 Agent 侧从未被告知
     * 「要给证据」——验收标准单边存在，执行体不知情，结果是主控反复打回、执行体反复重来。
     * 本段把验收标准前置告知执行体，让两侧对齐。
     * <p>
     * <b>为什么程序化注入而不写进 6 个 {@code .md}</b>：理由同 {@link #BLACKBOARD_GUIDE} ——
     * 存量用户 {@code ~/.easyClaw/subagents/*.md} 不会被播种覆盖，改种子对他们无效。
     */
    private static final String DELIVERY_DISCIPLINE_GUIDE = """

            ## 📌 交付纪律（你的汇报会被当作验收依据）
            你的回复不是说给用户听的，而是交给**主控**验收的。主控被明确要求
            「要证据不要结论」——**拿不出证据的完成声明一律按未完成处理**。
            所以下面几条直接决定你这次的活算不算数：

            1. **给证据，不要只给结论**。
               - 说改了代码 → 给出**文件路径 + 行号 + 改动要点**；
               - 说验证过 → 给出**实际执行的命令**与**关键输出**（贴结论行即可，不要整段日志）；
               - 说查清了某个事实 → 给出**依据来源**（文件路径:行号，或检索到的原文）。
               「已完成」「没问题」「应该可以」这类断言，在验收侧等同于什么都没说。
            2. **事实与推测必须分开写**。读到的、跑过的才是事实；没验证的一律标注
               「未验证」「推测」。**编造一个看起来合理的结论，比承认不知道危害大得多**，
               因为它会被主控当真并带进最终交付。
            3. **部分完成就如实说部分完成**。做完 3 项里的 2 项，就写清哪 2 项做完了（附证据）、
               哪 1 项没做、卡在哪里、当前处于什么中间状态。
               **不要用一句「已完成」盖住没做的部分**——这是最容易被追责的失败方式，
               主控在验收时一定会核对，盖不住。
            4. **做不到就明说，不要凑一个交付物**。缺少信息、权限不足、任务本身有歧义，
               直接说明卡点和你需要什么，比硬编一份看似完整的结果有价值得多。
            5. **不要顺手扩大范围**。任务没要求的重构、依赖引入、无关文件改动一律不做；
               确实发现了问题，写进汇报里提出来，交给主控决定。
            """;

    /**
     * 主控自身的 agentId，不作为可派遣的子 Agent 暴露（见 {@link #fromSpi}）。
     * <p>
     * 公开是为了让编排 UI 的名单口径（{@code ScenarioService.availableSubagents}）
     * 与运行时实际可派遣的名单共用同一个常量，避免两处各写一份 {@code "main"}
     * 字面值后悄悄漂移。
     */
    public static final String MAIN_AGENT_ID = "main";

    private final AgentScopeProperties properties;

    /**
     * SPI 注册表 —— 子 Agent 声明的<b>权威来源</b>。
     * <p>
     * 允许为 null：agent-core 的既有单测直接 {@code new SubagentLoader(props)}，
     * 不应因为引入 SPI 而被迫全部改造。为 null 时加载不到任何子 Agent。
     */
    private final AgentRegistry agentRegistry;

    /** 兼容构造器：不接 SPI，仅供单测使用。Spring 装配走两参构造器。 */
    public SubagentLoader(AgentScopeProperties properties) {
        this(properties, null);
    }

    @Autowired
    public SubagentLoader(AgentScopeProperties properties, AgentRegistry agentRegistry) {
        this.properties = properties;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 加载可派遣的子 Agent 声明，并施加场景绑定的<b>硬隔离</b>。
     * <p>
     * <b>唯一来源是 SPI</b>（{@link AgentRegistry}）。历史上的
     * {@code ~/.easyClaw/subagents/*.md} 扫描已整体下线，原因有三：
     * <ul>
     *   <li><b>静默失效无人察觉</b>——播种出去的 {@code .md} 带 UTF-8 BOM 时
     *       {@code content.startsWith("---")} 判定失败，整个 frontmatter 被当正文吞掉，
     *       {@code tools}/{@code steps}/{@code role} 全部丢失且不报错。</li>
     *   <li><b>失效方向是放权而非收权</b>——解析失败得到空工具名单，
     *       而 harness 把空名单解释为「不限制」，于是 planner 这类
     *       <b>刻意只读</b>的智能体反而拿到了主控的全部工具（含写文件）。
     *       声明文件失效本应让能力变小，实际却让能力变大，这是安全问题。</li>
     *   <li><b>两套事实来源必然漂移</b>——磁盘文件不随版本升级，代码改了它不改，
     *       用户机器上跑的是哪一份取决于历史残留，不可复现也不可支持。</li>
     * </ul>
     * <p>
     * <b>行为变更须知</b>：用户放在 {@code subagents/} 下的自定义 {@code .md}
     * 不再生效。磁盘文件本身不动（属用户家目录，删除不可逆且无收益），仅不再读取。
     *
     * @param binding 场景绑定；{@link ScenarioBinding#EMPTY} 表示不限制
     */
    public List<SubagentDeclaration> loadMerged(ScenarioBinding binding) {
        ScenarioBinding effective = binding == null ? ScenarioBinding.EMPTY : binding;
        return loadFromSpi(effective);
    }

    /**
     * 从 SPI 注册表构建子 Agent 声明 —— 子 Agent 名单的<b>唯一事实来源</b>。
     * <p>
     * {@code main} 不在其中：主控把自己列为可派遣对象会开出无意义的递归入口
     * （见 {@link #fromSpi}）。
     *
     * @return 声明列表；注册表未注入时返回空列表（仅测试场景，生产恒由 Spring 注入）
     */
    private List<SubagentDeclaration> loadFromSpi(ScenarioBinding binding) {
        List<SubagentDeclaration> declarations = new ArrayList<>();
        if (agentRegistry == null) {
            return declarations;
        }
        for (EasyClawAgent agent : agentRegistry.all()) {
            try {
                SubagentDeclaration decl = fromSpi(agent, binding);
                if (decl != null) {
                    declarations.add(decl);
                }
            } catch (Exception e) {
                // 单个智能体转换失败不能拖垮其余——与 AgentRegistry.discover 的容错取向一致
                log.warn("[SPI] 智能体 [{}] 转换为子 Agent 声明失败，已跳过: {}",
                        safeAgentId(agent), e.getMessage());
            }
        }
        if (!declarations.isEmpty()) {
            log.info("[SPI] 已装配 {} 个内置子 Agent: {}", declarations.size(),
                    declarations.stream().map(SubagentDeclaration::getName).toList());
        }
        return declarations;
    }

    /**
     * 把一个 {@link EasyClawAgent} 转换为 {@link SubagentDeclaration}。
     * <p>
     * 转换过程沿用既有的声明加工链（原 .md 路径同款）——智能体人格绑定、
     * 黑板段与交付纪律段注入、场景档位裁剪、黑板工具补白、步数下限抬升，
     * 一步都不少。SPI 只替换「声明从哪来」，不改变「声明怎么被加工」，
     * 因此 single 模式的既有行为可被原有测试逐条锁住。
     * <p>
     * <b>main 智能体不作为子 Agent 暴露</b>：它是主控自身，把自己列进可派遣清单
     * 会让模型产生「派一个我自己」的递归调用。
     *
     * @return 声明；{@code main} 或 agentId 非法时返回 null
     */
    private SubagentDeclaration fromSpi(EasyClawAgent agent, ScenarioBinding binding) {
        String agentId = agent.agentId();
        if (agentId == null || agentId.isBlank() || MAIN_AGENT_ID.equals(agentId)) {
            return null;
        }
        agentId = agentId.trim();

        // 人格：角色系统下线后（方案 C）完全由 SPI 内置文案提供，不再有 DB 角色覆盖层。
        String prompt = agent.prompt(new AgentContext(
                        null, null, binding != null && binding.isOrchestratedMode()))
                .persona();
        if (prompt == null || prompt.isBlank()) {
            prompt = "You are a helpful subagent named " + agentId + ".";
        }

        // 与 .md 路径逐字一致的两段程序化注入
        prompt = prompt + "\n" + BLACKBOARD_GUIDE;
        prompt = prompt + "\n" + DELIVERY_DISCIPLINE_GUIDE;

        int steps = Math.max(agent.stepFloor(), effectiveStepFloor(binding));

        SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                .name(agentId)
                .description(agent.profile().description())
                .inlineAgentsBody(prompt)
                .steps(steps)
                // 与 .md 路径一致：默认开启会话复用（该路径无 frontmatter 可覆盖）
                .persistSession(true);

        // 模型：SPI 声明优先，其次 application.yml 的 agents.<id> 配置（resolveAgentModel），
        // 都无偏好则不设置，由 harness 跟随全局默认模型（角色系统下线后原 DB 角色模型来源已移除）。
        ModelPreference modelPref = agent.modelPreference();
        if (!modelPref.hasPreference()) {
            modelPref = properties.resolveAgentModel(agentId);
        }
        if (modelPref.hasPreference()) {
            builder.model(modelPref.modelId());
        }

        // 工具/技能：先按 SPI 策略取声明值，再走与 .md 完全相同的裁剪链
        ToolPolicy toolPolicy = agent.toolPolicy();
        List<String> declaredTools = toolPolicy.unrestricted()
                ? null
                : new ArrayList<>(toolPolicy.allowed());
        List<String> effectiveTools = withBlackboardTools(
                restrictTools(agentId, normalizeToolNames(agentId, declaredTools), binding));
        if (effectiveTools != null) {
            builder.tools(effectiveTools);
        }

        SkillPolicy skillPolicy = agent.skillPolicy();
        List<String> declaredSkills = skillPolicy.unrestricted()
                ? null
                : new ArrayList<>(skillPolicy.allowed());
        List<String> effectiveSkills = restrictSkills(agentId, declaredSkills, binding);
        if (effectiveSkills != null) {
            builder.skills(effectiveSkills);
        }
        return builder.build();
    }

    private static String safeAgentId(EasyClawAgent agent) {
        try {
            return agent.agentId();
        } catch (Exception e) {
            return agent.getClass().getName();
        }
    }

    /**
     * 子 Agent 的迭代步数下限。
     * <p>
     * <b>所有模式统一抬到与主 Agent 相同的 {@code agent.maxIters}</b>（与
     * {@code agent.subagentSteps} 取较大者）。此前按模式分叉 —— 非 team 模式只取
     * {@code subagentSteps}（默认 30）—— 导致用户在 yml 里把 {@code max-iters} 调到
     * 500 时，single 模式的子 Agent <b>仍恒定 30 步且无任何提示</b>，大项目里
     * 「读几个文件 + 分析 + 改代码 + 验证」根本跑不完。
     * <p>
     * 步数不足会在 {@code ExceedMaxItersEvent} 处被硬截断 —— 表现为交付半成品
     * 而非报错，编排者还会把这份残缺产出当成成品汇总，故障非常隐蔽。子 Agent 承担的
     * 「实现一个模块」「评审一批文件」与主 Agent 的单轮任务量本就同量级，没有理由更短。
     * <p>
     * 防跑飞的职责交给 {@code max-iters} 本身：它是用户可见、可调的单一旋钮，
     * 调它即可同时约束主子两侧，不需要再有一个隐藏的第二档位。
     * <p>
     * 最终结果再与 {@link #ABSOLUTE_STEP_FLOOR} 取大：配置项是可被外部 yml 覆盖的，
     * 一旦被调到 30 以下，子 Agent 连「读几个文件 + 分析 + 写结论」都跑不完，
     * 截断故障会以「交付半成品」的形式静默扩散。这是产品下限，不接受配置突破。
     *
     * @param binding 保留参数：调用方按场景传入，便于未来按场景做差异化步数策略
     */
    private int effectiveStepFloor(ScenarioBinding binding) {
        int base = properties.getAgent().getSubagentSteps();
        int floor = Math.max(base, properties.getAgent().getMaxIters());
        return Math.max(floor, ABSOLUTE_STEP_FLOOR);
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

    private List<String> withBlackboardTools(List<String> effective) {
        if (effective == null) {
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

}
