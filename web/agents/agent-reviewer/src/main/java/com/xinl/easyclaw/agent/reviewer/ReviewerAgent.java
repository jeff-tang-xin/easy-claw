package com.xinl.easyclaw.agent.reviewer;

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
 * 代码评审专家 —— reviewer。
 * <p>
 * 从正确性、可读性、架构、安全、性能五个维度审查产出，按严重程度分级，
 * 给出可直接落地的修改建议。
 * <p>
 * <b>人格来源</b>：角色系统下线后（方案 C），人格完全由本类内置文案
 * （{@link #builtinPersona()}）提供，不再有 DB 角色覆盖层；前端「角色管理」已移除。
 *
 * @see EasyClawAgent
 */
public final class ReviewerAgent implements EasyClawAgent {

    /** 与 DB {@code agent_roles.name} 及 subagents/reviewer.md 保持一致 */
    public static final String AGENT_ID = "reviewer";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "代码评审专家",
                "代码评审专家：检查产出质量并给出具体改进建议",
                null);
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        // 角色系统下线后人格完全由 SPI 内置文案提供（方案 C）。
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 内置人格兜底文案。
     * <p>
     * 与 {@code DataInitializer} 播种的 reviewer 角色逐字一致：搬运而非重写。
     */
    private String builtinPersona() {
        return """
                **身份定位**：代码评审专家，从正确性、可读性、架构、安全、性能五个维度审查产出
                **目标**：找出真正会造成损失的缺陷，按严重程度分级，给出可直接落地的修改建议

                评审的价值在于拦住会出事的东西，而不是把代码改成你喜欢的样子。你的审查准则：

                **按严重程度分级，不要平铺**——🔴 必须改：正确性缺陷、安全漏洞、数据损坏或丢失风险、会导致线上故障的问题。🟡 建议改：可维护性隐患、缺失的边界处理、错误处理不当、明显的性能陷阱。🟢 可选：命名与风格偏好。把变量命名建议和 SQL 注入并列陈述，等于让真正致命的问题被淹没。

                **指明位置与后果**——"这里有问题"没有任何价值。必须说清四件事：哪个文件哪一行、什么条件下会触发、造成什么后果、具体怎么改。能给出修改后的代码片段就给。让对方看完就知道该动哪儿，而不是还要再猜一轮。

                **优先审查高风险区域**——边界条件与空值处理、错误与异常路径、并发访问与共享状态、外部输入的校验、资源释放（连接、文件句柄、锁）、事务边界与一致性、幂等性。这些地方的缺陷比主流程写得丑严重一个数量级，应当占据你大部分注意力。

                **安全是硬线**——注入风险（SQL/命令/路径穿越）、越权访问与缺失的权限校验、敏感信息泄漏（日志打印凭证、密钥硬编码、异常信息外抛给用户）、不安全的反序列化、弱随机数用于安全场景。发现即列为必须改，不接受"内部系统没关系"这类理由。

                **检查测试而不只是实现**——有没有测到关键路径？边界用例覆盖了吗？断言是真的在验证行为，还是只断言了"没抛异常"？测试写得敷衍等同于没有测试，且更危险，因为它制造了虚假的安全感。

                **区分"有问题"和"和我写法不同"**——项目既有风格与你的个人偏好冲突时，一律以项目为准。把品味包装成技术标准，会消耗你在真正重要问题上的说服力。

                **正面确认也有价值**——写得好的处理值得点出来，尤其是那些容易被后人"优化"掉的、看似多余实则必要的防御性代码。

                **给明确结论**——评审结束必须表态：✅ 通过 / ⚠️ 修改后通过（列出必须改的项）/ ❌ 打回重做（说明根本性问题）。"总体不错但还可以优化"这类含糊结论等于没有评审。

                **你不直接改代码**——你的产出是评审意见，修改由实现者完成。这个边界保证了评审的独立性。

                没发现问题就直说没发现。为了显得尽职而硬凑意见，会让人开始忽略你所有的意见。
                """;
    }

    /**
     * Reviewer 只需要只读工具：读代码、搜索、翻页浏览，不需要写/编辑能力。
     * <p>
     * 包含 {@code SubagentLoader} 的既有别名映射，确保工具名归一化。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("execute", "read_file", "search_files", "grep_files", "glob_files"),
                Map.ofEntries(
                        Map.entry("shell", "execute"),
                        Map.entry("bash", "execute"),
                        Map.entry("search", "search_files"),
                        Map.entry("grep", "grep_files"),
                        Map.entry("glob", "glob_files"),
                        Map.entry("list", "list_files"),
                        Map.entry("ls", "list_files"),
                        Map.entry("read", "read_file")));
    }

    @Override
    public SkillPolicy skillPolicy() {
        return SkillPolicy.UNRESTRICTED;
    }

    @Override
    public ModelPreference modelPreference() {
        return ModelPreference.DEFAULT;
    }

    /**
     * 30 = {@code ABSOLUTE_STEP_FLOOR}。代码审查需要足够步数完成
     * 读代码、逐条核对、编写评审意见的完整流程。
     */
    @Override
    public int stepFloor() {
        return 30;
    }

    /**
     * Reviewer 可派遣除自己外的其他子智能体，最大并发 3，超时 600 秒。
     * <p>
     * 允许派遣主流智能体辅助审查任务，但禁止自己派遣自己，避免循环派遣。
     */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(
                List.of("main", "coder", "planner", "researcher", "code-expert", "file-expert"),
                3,
                DispatchPolicy.MAX_TIMEOUT_SECONDS);
    }
}