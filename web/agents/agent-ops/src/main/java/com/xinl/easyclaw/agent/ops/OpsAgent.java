package com.xinl.easyclaw.agent.ops;

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
 * 运维智能体。
 * <p>
 * 运维场景（mode=ops）的专属主控人格：通过 {@code remote_shell} 在远程 Linux
 * 服务器上执行操作、排查问题。与通用 {@code MainAgent}（全栈工程人格）严格区分——
 * 运维场景不需要编程工程的方法论说教，说话方式以「克制、动作优先」为第一准则。
 * <p>
 * <b>能力边界的三层防线</b>（本类只承担第一层）：
 * <ol>
 *   <li><b>声明层</b>：{@link #toolPolicy()} 仅声明 {@code remote_shell}；</li>
 *   <li><b>模式层</b>：{@code OpsOrchestrator.minimalToolkit()} 声明最小工具集，
 *       api 层据此装配仅含 remote_shell 的 toolkit；</li>
 *   <li><b>硬约束层</b>：工作区 {@code type=ops}（创建后不可变）直接判定收缩 toolkit，
 *       SPI 静默降级（重启后 classpath 缺 mode 模块）时兜底——宁可少给工具，不可多给。</li>
 * </ol>
 * 主链路（主智能体装配）不消费 {@link #toolPolicy()}（该声明仅在子 Agent 装载路径生效），
 * 因此运维主控的工具集实际由第 2/3 层锁死；本声明保证即使 ops 被当作子 Agent 装载，
 * 能力边界同样正确。
 */
public final class OpsAgent implements EasyClawAgent {

    /** 与场景种子（SystemDataSeeder 的 ops 场景）及 OpsOrchestrator.mainAgentId 保持一致 */
    public static final String AGENT_ID = "ops";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "OPS",
                "运维智能体：通过 remote_shell 管理远程 Linux 服务器，动作优先、输出克制",
                "🖥️");
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 运维人格：克制是硬约束，不是风格偏好。
     * <p>
     * 「不复述流程 / 不抄回显 / 不解释自明命令」三条直接针对运维场景最高频的
     * 啰嗦来源——模型把确认弹窗与终端回显的内容再复述一遍。工作方法（先只读后动手、
     * 高危示警）与场景 systemPrompt 分工：这里管「怎么说话」，场景管「怎么干活」。
     */
    private String builtinPersona() {
        return """
                # 身份
                OPS —— 运维智能体。

                # 目标
                用最少的往返查清状态、解决问题。动作优先，输出克制。

                # 说话方式（强制）
                - 直接给结论与下一步动作；不复述流程、不自我说明、不预告你将做什么。
                - 终端已实时回显输出，不要抄进回复；只给解读与结论。
                - 确认弹窗已展示命令本身，命令含义不自明时才用一句话解释。
                - 高危操作（删除、重启、改配置、杀进程）先用一句话提示风险与回滚方式；其余直接执行。
                - 语言跟随用户。

                # 执行纪律
                - 一次只给一条命令，等确认执行后再看回显决定下一步；不预先堆叠多条待确认命令。
                - 状态不明先只读：ps、df、free、systemctl status、journalctl、ss、top、uptime、dmesg。
                - 不编造输出或状态；不确定就继续用只读命令确认。
                - 命令失败不臆断原因，给最小下一步，或说明需要哪条只读命令/哪项信息。
                - 不假装执行、不绕过用户确认、不解释工具调用机制。

                # 高危操作
                范围：删除/覆盖文件、重启/关机、改配置、杀进程、改权限、防火墙变更、磁盘/分区操作、内核参数、数据库写操作。
                模板：风险：…；回滚：…。然后给出命令，等待确认。

                # 回复格式
                结论：一句话。
                下一步：一句话。
                命令：`...`
                风险/回滚：仅高危时给。
                然后停下，等待用户确认。
                """;
    }

    /**
     * 仅 remote_shell。别名必须带上：harness 按名严格相等裁剪工具，
     * {@code shell}/{@code ssh} 惯用写法对不上真名会被静默移除。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("remote_shell"),
                Map.of("shell", "remote_shell",
                        "ssh", "remote_shell",
                        "bash", "remote_shell"));
    }

    /**
     * 运维场景无方法论类 skill 需求。注意空列表语义是「不限制」而非「全禁」；
     * 运维主控的 skill 注入实际由场景绑定（ScenarioBinding.skills）控制，
     * 本声明仅在 ops 被当作子 Agent 装载时作为兜底。
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
     * 30 = {@code ABSOLUTE_STEP_FLOOR}。运维排查（查状态 → 定位 → 处置 → 验证）
     * 的往返轮次与通用任务同量级，取下限即可。
     */
    @Override
    public int stepFloor() {
        return 30;
    }

    /** 运维智能体不派遣子 Agent：远程操作是单执行体串行动作，无并行拆解需求 */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(java.util.List.of(), 0, 0);
    }
}
