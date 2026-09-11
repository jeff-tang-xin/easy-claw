package com.xinl.easyclaw.agent.researcher;

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
 * 研究分析专家。
 * <p>
 * 把模糊问题转化为结构化的、有依据的结论。它不编造、不堆砌搜索结果的搬运工——
 * 每条结论必须有来源，信息来源必须可追溯，推断与事实必须可区分。
 * <p>
 * <b>人格来源</b>：角色系统下线后（方案 C），人格完全由本类内置文案
 * （{@link #builtinPersona()}）提供，不再有 DB 角色覆盖层；前端「角色管理」已移除。
 */
public final class ResearcherAgent implements EasyClawAgent {

    /** 与 DB {@code agent_roles.name} 及 subagents/researcher.md 文件名保持一致 */
    public static final String AGENT_ID = "researcher";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "研究分析师",
                "给出有依据、可追溯、结论明确的调研结果，把事实与推断清楚分开",
                "🔍");
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        // 角色系统下线后人格完全由 SPI 内置文案提供（方案 C）。
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 内置人格兜底文案。
     * <p>
     * <b>与 {@code DataInitializer} 播种的 researcher 角色逐字一致</b>——两处文案若漂移，
     * 用户会遇到「删掉 DB 角色后智能体表现突然变了」的诡异现象。
     * 格式对齐 {@code RolePromptComposer.compose()} 的渲染结果。
     * <p>
     * 只写「你是谁、你怎么干活」，不重抄工具协议与安全规范。
     */
    private String builtinPersona() {
        return """
                **身份定位**：信息研究分析师，擅长资料检索、交叉验证与方案对比
                **你的目标**：给出有依据、可追溯、结论明确的调研结果，把事实与推断清楚分开
                **背景设定**：
                你的价值不在于搬运信息，而在于判断哪些信息可信、哪些结论站得住。你的工作方式：

                **先拆解问题再检索**——把模糊问题拆成几个可查证的具体子问题，再逐个求证。上来就搜宽泛关键词只会得到一堆正确的废话。检索词要贴近资料原文可能的措辞，而不是用户的口语表达。

                **交叉验证**——重要结论至少要有两个独立来源印证。注意区分"独立来源"和"互相转载"：三篇文章都引用同一条推文，那只是一个来源。官方文档、源码、规范原文优先于博客与二手解读；内容农场和 SEO 站点的内容一律标注为待证实。

                **警惕时效性**——技术领域三年前的最佳实践可能已经过时，API 可能已废弃，安全建议可能已被推翻。引用时留意发布时间与版本号，明确说明这条结论对应的是哪个版本。

                **事实、推断、观点三分**——"文档里写的"是事实，"由此推测"是推断，"我认为更好"是观点。三者在表述上必须可区分，绝不把推断包装成事实。这是研究工作的底线：一旦混淆，后续所有基于它的决策都建立在流沙上。

                **对比方案要落到差异上**——列参数表没有价值，要指出真正影响选择的分歧点：什么场景下 A 明显更好，什么条件下 B 才划算，两者的失效边界各在哪里。同时给出明确倾向和理由，而不是罗列完让用户自己选。

                **说清结论的适用前提**——任何结论都有成立条件。要说明在什么前提下有效、什么情况下会失效、有哪些已知的反例或争议。脱离前提的绝对化结论是有害的。

                **承认边界**——查不到就说查不到，证据不足就说不足以支撑结论。编造一个看似合理的答案，比承认无知的危害大得多——因为前者会被当真。

                **标注来源**——引用外部资料时给出可访问的链接，让用户能自行核验。这既是严谨，也是把判断权交还给用户。

                输出结构：先给结论，再给依据（含来源），最后列出不确定项、争议点与建议的后续动作。

                以上身份设定决定你的专业视角、判断标准与说话方式，在整个会话中保持一致。
                """;
    }

    /**
     * 研究专家需要综合本地与网络资源。
     * <p>
     * {@code web_search}/{@code fetch_webpage} 为联网检索提供来源素材；
     * {@code knowledge_list}/{@code knowledge_read} 访问本地知识库；
     * {@code memory_search}/{@code memory_get} 只读跨会话记忆；
     * {@code execute} 供运行只读探查命令（如 {@code git log --oneline} 看版本历史）。
     * <p>
     * 不给 {@code write_file}/{@code edit_file}：研究只产出结论，不落地修改。
     * 不给 {@code memory_save}：子 Agent 的沉淀归口黑板、由主 Agent 统一写记忆，
     * 防止多写手提权混写主 MEMORY.md（与装配层子 Agent disableMemoryHooks 同向）。
     * 别名映射必须带上，避免 harness 按名严格相等裁剪时工具凭空消失。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("web_search", "fetch_webpage", "read_file", "search_files",
                        "grep_files", "glob_files", "execute",
                        "memory_search", "memory_get",
                        "list_files", "knowledge_list", "knowledge_read"),
                Map.of("web_fetch", "fetch_webpage",
                        "shell", "execute",
                        "bash", "execute",
                        "search", "search_files",
                        "grep", "grep_files",
                        "glob", "glob_files",
                        "read", "read_file",
                        "list", "list_files",
                        "ls", "list_files"));
    }

    /**
     * 研究分析不需要编程类或重构类技能，但可能需要了解架构背景与质量标准来辅助判断。
     * {@code UNRESTRICTED} 仅主控可用，子智能体逐项声明。
     * <p>
     * <b>已与 skill 注册表逐项比对（8 个可用 skill）</b>，下列 3 个 id 均真实存在。
     * 刻意排除的 5 个：{@code karpathy-guidelines}/{@code clean-code}/{@code code-refactor}
     * 是编码类方法论，研究者不写代码；{@code cursor-rules} 是主控协作话术规范；
     * {@code vercel-react-best-practices} 是 React 具体编码手法。
     * 研究者只需要知道「架构约束/质量标准/部署环境」来辅助判断与评估风险。
     * <p>
     * <b>匹配规则须注意</b>：harness 的 {@code HarnessSkillMiddleware.applySkillFilter}
     * 按 {@code skill.getName()}（<b>裸名</b>）精确匹配，因此这里写裸名是对的。
     * 不要误用 core 侧 {@code AgentSkill.getSkillId()} 的
     * {@code name + "_" + source} 形式（如 {@code clean-code_filesystem-.easyClaw_skills}）——
     * 那是另一条渲染路径的标识，写进来会全部匹配失败、静默丢光所有 skill。
     */
    @Override
    public SkillPolicy skillPolicy() {
        return new SkillPolicy(List.of("backend-architecture", "frontend-quality", "devops-cicd"));
    }

    /** 跟随全局默认模型 */
    @Override
    public ModelPreference modelPreference() {
        return ModelPreference.DEFAULT;
    }

    /** 30 = {@code ABSOLUTE_STEP_FLOOR} */
    @Override
    public int stepFloor() {
        return 30;
    }

    /**
     * 可派遣除自己外的其他智能体：调研时常需要 code-expert 帮忙读某段源码的意图，
     * 或 planner 把调研转化为执行计划。
     * <p>
     * 不含 {@code researcher} 自身——自派遣无意义。
     * {@code timeoutSeconds=600} 取框架硬上限，避免调研类耗时的子任务被默认值掐断。
     */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(
                List.of("main", "coder", "reviewer", "planner", "code-expert", "file-expert"),
                3,
                DispatchPolicy.MAX_TIMEOUT_SECONDS);
    }
}