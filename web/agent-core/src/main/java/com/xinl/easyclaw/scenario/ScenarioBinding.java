package com.xinl.easyclaw.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.base.orchestration.AgentOrchestrator;
import com.xinl.easyclaw.base.orchestration.OrchestrationModes;
import com.xinl.easyclaw.base.profile.ScenarioProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 场景能力绑定的<b>不可变解析结果</b>：把 {@link ScenarioProfile} 里几个 JSON 文本列
 * 转成可直接使用的名字列表 + 能力档位。
 * <p>
 * 独立成类的原因：绑定信息要同时喂给三个去处（toolkit 过滤、子 Agent 声明裁剪、
 * 提示词推荐），若各处自行解析 JSON，容错行为迟早不一致。
 * <p>
 * <b>约束语义</b>（与产品共识一致）：
 * <ul>
 *   <li>{@link #mcpServices()} —— 硬约束，未绑定的 MCP 工具不进 toolkit</li>
 *   <li>{@link #skills()} / {@link #subagents()} —— 对主智能体是<b>软</b>提示（仅推荐），
 *       对子智能体是<b>硬</b>隔离（写进 SubagentDeclaration 的 skills 白名单）</li>
 * </ul>
 * <b>空绑定 = 不限制</b>，保证既有场景零影响。
 */
public final class ScenarioBinding {

    private static final Logger log = LoggerFactory.getLogger(ScenarioBinding.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无绑定单例：任何 isEmpty() 判断都为真，调用方走原有不限制路径 */
    public static final ScenarioBinding EMPTY =
            new ScenarioBinding(List.of(), List.of(), List.of(),
                    CapabilityTier.STANDARD, false, List.of(), false, true);

    private final List<String> skills;
    private final List<String> subagents;
    private final List<String> mcpServices;
    private final CapabilityTier tier;
    /**
     * 场景是否<b>显式</b>配置了档位。
     * <p>必须与「解析结果恰好等于默认值 STANDARD」区分开：未配置时不能裁剪工具，
     * 否则既有场景的子 Agent 会静默失去 execute / 子 Agent 调度等能力。
     */
    private final boolean tierExplicit;
    /** 场景绑定 MCP 服务展开后的工具名（由上层注入，本类不访问数据库） */
    private final List<String> mcpTools;
    /**
     * 当前激活场景是否为编排模式（非单智能体）。
     * <p>
     * 决定子 Agent 的迭代步数是否对齐主 Agent —— 编排模式下子 Agent 承担的是
     * 完整子任务而非一次问答，用默认 30 步会中途被截断。
     * <p>
     * <b>判据是「模式不是 single」而非「模式等于 team」</b>，
     * 这样新增 schedule 等编排型模式时自动生效，无需再改这里。
     * 由 {@link OrchestrationModes#isOrchestrated} 统一判定。
     */
    private final boolean orchestratedMode;
    /**
     * 本模式是否允许主控派遣子智能体。
     * <p>
     * 与 {@link OrchestrationModes} 的模式自声明同源：{@code AgentOrchestrator
     * .subagentDispatchEnabled()} 的解析结果。false 时主控的子 Agent 名册整体为空
     * （如 ops：远程操作是单执行体串行动作，无并行拆解需求）。
     */
    private final boolean subagentDispatchEnabled;

    private ScenarioBinding(List<String> skills, List<String> subagents,
                            List<String> mcpServices, CapabilityTier tier,
                            boolean tierExplicit, List<String> mcpTools,
                            boolean orchestratedMode, boolean subagentDispatchEnabled) {
        this.skills = Collections.unmodifiableList(skills);
        this.subagents = Collections.unmodifiableList(subagents);
        this.mcpServices = Collections.unmodifiableList(mcpServices);
        this.tier = tier;
        this.tierExplicit = tierExplicit;
        this.mcpTools = Collections.unmodifiableList(mcpTools);
        this.orchestratedMode = orchestratedMode;
        this.subagentDispatchEnabled = subagentDispatchEnabled;
    }

    /**
     * 从场景实体解析绑定；{@code scenario} 为 null 时返回 {@link #EMPTY}。
     * <p>解析失败按「无绑定」降级 —— 与 {@code ScenarioResolver} 一致，
     * 脏数据不应让工作区起不来。
     */
    public static ScenarioBinding from(ScenarioProfile scenario) {
        if (scenario == null) {
            return EMPTY;
        }
        String rawTier = scenario.getCapabilityTier();
        boolean explicit = rawTier != null && !rawTier.isBlank();
        // 脏数据告警：mode 显式配了但没注册（拼错/模块未上 classpath/模式已下线）。
        // 此时下面的 isOrchestrated 会静默返回 false，表现为「配了团队模式却按单智能体跑」，
        // 没有这条日志就只能靠用户觉得「行为不对」来发现。不阻断——降级跑通比起不来强。
        if (OrchestrationModes.isUnregistered(scenario.getMode())) {
            log.warn("[场景] mode=[{}] 未注册，已按单智能体降级运行（检查拼写或对应 mode 模块是否在 classpath）。已注册: {}",
                    scenario.getMode(), OrchestrationModes.modeIds());
        }
        return new ScenarioBinding(
                parseNameArray(scenario.getSkills()),
                parseNameArray(scenario.getSubagents()),
                parseNameArray(scenario.getMcpServices()),
                CapabilityTier.parse(rawTier, CapabilityTier.STANDARD),
                explicit,
                List.of(),
                OrchestrationModes.isOrchestrated(scenario.getMode()),
                OrchestrationModes.find(scenario.getMode())
                        .map(AgentOrchestrator::subagentDispatchEnabled)
                        .orElse(true));
    }

    /**
     * 宽松解析名字数组：优先按 JSON 数组解析，失败则退回逗号分隔。
     * <p>兜底是必要的 —— 这几列可能由人工直接写入数据库或早期 API 传入裸字符串。
     */
    static List<String> parseNameArray(String raw) {
        List<String> names = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return names;
        }
        String text = raw.trim();
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.isTextual()) {
                        addIfPresent(names, node.asText());
                    }
                }
                return names;
            }
            if (root.isTextual()) {
                addIfPresent(names, root.asText());
                return names;
            }
            // 合法 JSON 但既非数组也非字符串（数字/对象/布尔）→ 明确不是名字列表。
            // 此处必须直接返回：若继续走逗号兜底，"{\"k\":\"v\"}" 会被切成
            // {、k、v、} 这样的垃圾条目，比空列表更糟（会污染白名单）。
            log.warn("绑定字段是 JSON 但不是名字数组，已忽略: {}", text);
            return names;
        } catch (Exception e) {
            log.debug("绑定字段非合法 JSON，按逗号分隔兜底: {}", text);
        }
        for (String part : text.split("[,\\[\\]\"']")) {
            addIfPresent(names, part);
        }
        return names;
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && !target.contains(trimmed)) {
            target.add(trimmed);
        }
    }

    public List<String> skills() {
        return skills;
    }

    public List<String> subagents() {
        return subagents;
    }

    public List<String> mcpServices() {
        return mcpServices;
    }

    public CapabilityTier tier() {
        return tier;
    }

    /** 场景绑定 MCP 服务展开后的工具名 */
    public List<String> mcpTools() {
        return mcpTools;
    }

    /**
     * 返回一个附带 MCP 展开工具名的副本（值对象保持不可变）。
     * <p>展开需要查库，由 {@code McpToolExpander} 在上层完成后注入，
     * 避免本类依赖持久层。
     */
    public ScenarioBinding withMcpTools(Collection<String> expanded) {
        List<String> safe = (expanded == null) ? List.of() : List.copyOf(expanded);
        return new ScenarioBinding(
                new ArrayList<>(skills), new ArrayList<>(subagents),
                new ArrayList<>(mcpServices), tier, tierExplicit, new ArrayList<>(safe),
                orchestratedMode, subagentDispatchEnabled);
    }

    /** 是否存在 MCP 硬约束（决定 toolkit 是否走白名单路径） */
    public boolean hasMcpBinding() {
        return !mcpServices.isEmpty();
    }

    /** 档位是否由场景显式配置 */
    public boolean hasExplicitTier() {
        return tierExplicit;
    }

    /**
     * 当前激活场景是否为编排模式（非单智能体）。
     * <p><b>注意</b>：{@link #EMPTY}（无激活场景）返回 false，因此单智能体场景与
     * 「场景查询失败降级」都走原有默认步数，不受影响。
     */
    public boolean isOrchestratedMode() {
        return orchestratedMode;
    }

    /**
     * 本模式是否允许主控派遣子智能体。
     * <p><b>注意</b>：{@link #EMPTY}（无激活场景）返回 true，因此单智能体场景与
     * 「场景查询失败降级」都走原有名册装配，不受影响。
     */
    public boolean isSubagentDispatchEnabled() {
        return subagentDispatchEnabled;
    }

    /**
     * 是否需要对子 Agent 施加工具白名单。
     * <p>仅在<b>显式</b>配置档位或绑定了 MCP 时成立 —— 未配置即不裁剪，保持向后兼容。
     */
    public boolean hasToolBinding() {
        return tierExplicit || hasMcpBinding();
    }

    /** 是否存在 skill 硬隔离（决定子 Agent 是否施加 SkillFilter） */
    public boolean hasSkillBinding() {
        return !skills.isEmpty();
    }

    /** 完全无绑定：调用方可直接短路到原有「不限制」逻辑 */
    public boolean isEmpty() {
        return skills.isEmpty() && subagents.isEmpty() && mcpServices.isEmpty() && !tierExplicit;
    }

    @Override
    public String toString() {
        return "ScenarioBinding{skills=" + skills + ", subagents=" + subagents
                + ", mcpServices=" + mcpServices + ", tier=" + tier
                + (tierExplicit ? "(explicit)" : "(default)")
                + (orchestratedMode ? ", mode=orchestrated" : "") + '}';
    }
}
