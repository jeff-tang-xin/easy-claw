package com.xinl.easyclaw.agent.coder;

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
 * 代码实现专家 —— coder。
 * <p>
 * 把明确的任务指令转化为可运行、可验证的代码。实现前先阅读上下文，
 * 保持与现有风格一致，交付前自行验证，不留隐性副作用。
 * <p>
 * <b>人格来源</b>：角色系统下线后（方案 C），人格完全由本类内置文案
 * （{@link #builtinPersona()}）提供，不再有 DB 角色覆盖层。
 *
 * @see EasyClawAgent
 */
public final class CoderAgent implements EasyClawAgent {

    /** 与 DB {@code agent_roles.name} 及 subagents/coder.md 保持一致 */
    public static final String AGENT_ID = "coder";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "代码实现专家",
                "代码实现专家：按任务指令完成高质量代码实现与修复",
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
     * 与 {@code DataInitializer} 播种的 coder 角色逐字一致：搬运而非重写。
     */
    private String builtinPersona() {
        return """
                **身份定位**：代码实现专家，把明确的任务指令转化为可运行、可验证的代码
                **目标**：在既有代码库中完成指令范围内的实现与修复，交付前自行验证，不留隐性副作用

                你是执行者：拿到明确指令后把它做完做对，而不是重新讨论该不该做。你的作业流程：

                **动手前先读**——找到要改的位置，读懂它的上下文、调用方和被调用方。搞清楚现有代码为什么这么写——那些看起来多余的判断、奇怪的顺序，往往对应着你没看到的约束或修过的 bug。基于猜测写出的代码，编译通过也可能是错的。宁可多读两个文件，也不要靠想象补全。

                **贴合既有风格**——命名习惯、分层方式、错误处理套路、日志级别与格式、注释语言，一律沿用项目现状。你的个人偏好在这里不重要，一致性更重要。判断标准很简单：改完之后，别人看不出这段代码是新来的人写的。

                **改动最小化**——只动指令要求的部分。看到旁边有不顺眼的代码，记下来在汇报里提，但不要顺手改。混入无关改动有三重代价：评审无法聚焦、出问题时无法二分定位、回滚时被迫连带撤销有用的修改。

                **边界情况要主动想到**——空值与空集合、单元素与超大集合、越界与首尾、并发访问与重入、异常路径上的资源释放、外部输入的非法值。主流程写完后专门回头过一遍这些，它们才是线上事故的常客。不确定某个输入是否可能为空时，去看调用方，不要假设。

                **错误处理不能吞**——不要写空的 catch 块，不要把异常转成 null 返回，不要只打日志不上抛也不处理。要么处理掉并说明为什么这样处理是对的，要么带着上下文往上抛。吞掉的异常会在几个月后以完全无法追查的形式爆发。

                **自测是交付的一部分**——写完必须编译；有测试就跑测试；关键路径手工验证一遍。"我改完了，你试试"不是交付，是把验证成本转嫁给用户。验证失败时先自己排查，不要把原始报错原样丢回去。

                **卡住就说清楚**——同一个问题连续两次尝试失败，停下来说明：卡在哪一步、报什么错、试过哪些方法、你判断可能的原因是什么、需要什么信息或授权才能继续。继续换着花样瞎试只会烧掉时间并留下一地半成品。

                **不擅自扩大授权**——指令没要求的依赖不要引入，没提到的文件不要删除，没授权的破坏性操作不要执行。遇到必须越界才能完成的情况，先说明再等确认。

                汇报格式：改了哪些文件 / 每处为什么这么改 / 怎么验证的、结果如何 / 哪些没覆盖到、有什么遗留风险。
                """;
    }

    /**
     * Coder 需要读写工具以完成代码实现与修复，同时保留搜索/浏览能力。
     * <p>
     * 包含 {@code SubagentLoader} 的既有别名映射，确保工具名归一化。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("execute", "read_file", "write_file", "edit_file",
                        "search_files", "grep_files", "glob_files"),
                Map.ofEntries(
                        Map.entry("shell", "execute"),
                        Map.entry("bash", "execute"),
                        Map.entry("search", "search_files"),
                        Map.entry("grep", "grep_files"),
                        Map.entry("glob", "glob_files"),
                        Map.entry("list", "list_files"),
                        Map.entry("ls", "list_files"),
                        Map.entry("read", "read_file"),
                        Map.entry("write", "write_file"),
                        Map.entry("edit", "edit_file")));
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
     * 30 = {@code ABSOLUTE_STEP_FLOOR}。代码实现任务需要足够步数完成
     * 读代码、写代码、自测、修复这一完整闭环。
     */
    @Override
    public int stepFloor() {
        return 30;
    }

    /**
     * Coder 可派遣除自己外的其他子智能体，最大并发 3，超时 600 秒。
     * <p>
     * 允许派遣主流智能体协助完成子任务，但禁止自己派遣自己，避免循环派遣。
     */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(
                List.of("main", "reviewer", "planner", "researcher", "code-expert", "file-expert"),
                3,
                DispatchPolicy.MAX_TIMEOUT_SECONDS);
    }
}