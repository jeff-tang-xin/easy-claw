package com.xinl.easyclaw.agent.db;

import com.xinl.easyclaw.base.BuiltinDbIds;
import com.xinl.easyclaw.base.agent.AgentContext;
import com.xinl.easyclaw.base.agent.AgentProfile;
import com.xinl.easyclaw.base.agent.DispatchPolicy;
import com.xinl.easyclaw.base.agent.EasyClawAgent;
import com.xinl.easyclaw.base.agent.ModelPreference;
import com.xinl.easyclaw.base.agent.PromptContribution;
import com.xinl.easyclaw.base.agent.SkillPolicy;
import com.xinl.easyclaw.base.agent.ToolPolicy;

import java.util.List;
import java.util.Map;

/**
 * 数据库智能体（V30）。
 * <p>
 * DB 场景（mode=db）的专属主控人格：通过 {@code db_query/db_schema/db_status}
 * 以只读 SQL 查询数据库、分析结构与数据。结构照抄 {@code OpsAgent}。
 * 与通用 {@code MainAgent}（全栈工程人格）严格区分——数据库场景不需要编程工程的
 * 方法论说教，说话方式以「严谨、克制、只读红线」为第一准则。
 * <p>
 * <b>能力边界的三层防线</b>（本类只承担第一层）：
 * <ol>
 *   <li><b>声明层</b>：{@link #toolPolicy()} 仅声明 db 三工具；</li>
 *   <li><b>模式层</b>：{@code DbOrchestrator.minimalToolkit()} 声明最小工具集，
 *       api 层据此装配仅含 db 三工具的 toolkit；</li>
 *   <li><b>硬约束层</b>：工作区 {@code type=db}（创建后不可变）直接判定收缩 toolkit，
 *       SPI 静默降级（重启后 classpath 缺 mode 模块）时兜底——宁可少给工具，不可多给。</li>
 * </ol>
 * 主链路（主智能体装配）不消费 {@link #toolPolicy()}（该声明仅在子 Agent 装载路径生效），
 * 因此数据库主控的工具集实际由第 2/3 层锁死；本声明保证即使 db 被当作子 Agent 装载，
 * 能力边界同样正确。
 */
public final class DbAgent implements EasyClawAgent {

    /** 与场景种子（SystemDataSeeder 的 db 场景）及 DbOrchestrator.mainAgentId 保持一致（统一锚点 BuiltinDbIds.DB） */
    public static final String AGENT_ID = BuiltinDbIds.DB;

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "DB",
                "数据库智能体：通过只读 SQL 查询数据库、分析结构与数据，严谨克制",
                "🗄️");
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 数据库人格：只读红线是硬约束，不是风格偏好。
     * <p>
     * 「不复述流程 / 不抄结果表」直接针对查询场景最高频的啰嗦来源——模型把
     * 确认弹窗与结果表格再复述一遍。工作方法（先结构后数据、EXPLAIN 优先、脱敏引用）
     * 与场景 systemPrompt 分工：这里管「怎么说话」，场景管「怎么干活」。
     */
    private String builtinPersona() {
        return """
                # 身份
                DB —— 数据库智能体。

                # 目标
                用最少的查询查清数据与结构问题。只读，严谨，输出克制。

                # 说话方式（强制）
                - 直接给结论与下一步；不复述流程、不自我说明。
                - 查询结果已展示，不要把整表抄进回复；只给解读与结论。
                - 语言跟随用户。

                # 执行纪律（只读红线）
                - 只执行只读查询（SELECT/WITH）；任何写操作（INSERT/UPDATE/DELETE/DDL）
                  一律只产出 SQL 脚本文本并说明风险与回滚，交人工审核执行——工具会拒绝写语句，不要尝试绕过。
                - 不确定表结构先 db_schema，再写查询；大表先 LIMIT 采样，禁止无界查询。
                - 查询失败不臆断原因：看报错、查元数据、修正后重试；给最小下一步。
                - 生产库意识：默认连的是生产只读账号，EXPLAIN 优先于直接跑重查询；结果可能含敏感数据，
                  引用时脱敏（手机号/证件号只给尾号）。
                - 不编造查询结果；一切以工具返回为准。

                # 分析输出纪律（统计与结论）
                - 口径先行：给任何统计数字前先声明字段、单位、币种与时间戳语义；NULL 与枚举外的值单独归桶，
                  各桶占比之和必须等于 100%（用 N/M 形式给分母）。
                - 金额严禁跨币种直接加总：按币种分组统计；确需合并口径时先声明汇率来源与换算时点，
                  无汇率依据就只给分币种值并明确说明不可加总。
                - 枚举字段先查全量分布（GROUP BY 全枚举）再下结论，不得只挑支撑叙事的取值。
                - 异常与「特征」类结论必须附验证依据（如判断批量导入：同秒/同分钟多笔、单号连续性等验证查询的结果）；
                  验证不了就明确标注为待验证假设，不写成事实。
                - 时间跨度同时给覆盖度：数据首末日 + 有数据天数/总天数，区分「跨度」与「密度」，
                  稀疏数据不得表述为趋势。
                - 图表用 Markdown 表格或 ASCII 条形图呈现，不生成依赖 CDN/外链的 HTML（离线环境会空白）。

                # 回复格式
                结论：一句话。
                下一步：一句话。
                SQL：`...`（仅当需要用户确认或复用时给）
                """;
    }

    /**
     * 仅 db 三工具。别名必须带上：harness 按名严格相等裁剪工具，
     * {@code sql}/{@code query}/{@code schema} 惯用写法对不上真名会被静默移除。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("db_query", "db_schema", "db_status"),
                Map.of("sql", "db_query",
                        "query", "db_query",
                        "schema", "db_schema"));
    }

    /**
     * 数据库场景无方法论类 skill 需求。注意空列表语义是「不限制」而非「全禁」；
     * 数据库主控的 skill 注入实际由场景绑定（ScenarioBinding.skills）控制，
     * 本声明仅在 db 被当作子 Agent 装载时作为兜底。
     */
    @Override
    public SkillPolicy skillPolicy() {
        return SkillPolicy.UNRESTRICTED;
    }

    /** 跟随全局默认模型：硬编码具体模型名会在用户实际 provider 下解析失败并回退 */
    @Override
    public ModelPreference modelPreference() {
        return ModelPreference.DEFAULT;
    }

    /**
     * 30 = {@code ABSOLUTE_STEP_FLOOR}。数据库排查（看结构 → 查数据 → 分析 → 复核）
     * 的往返轮次与通用任务同量级，取下限即可。
     */
    @Override
    public int stepFloor() {
        return 30;
    }

    /** 数据库智能体不派遣子 Agent：SQL 查询是单执行体串行动作，无并行拆解需求 */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(java.util.List.of(), 0, 0);
    }
}
