package com.xinl.easyclaw.agent.planner;

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
 * 任务规划专家。
 * <p>
 * 把模糊需求拆解为有序、可验证、可分派的执行清单。它<b>只规划不执行</b>——
 * 这条边界不是风格偏好而是职责隔离：规划者顺手把活干了，就没人再对计划的
 * 完整性负责，后续执行者也失去了可验收的依据。
 * <p>
 * <b>人格来源</b>：角色系统下线后（方案 C），人格完全由本类内置文案
 * （{@link #builtinPersona()}）提供，不再有 DB 角色覆盖层；前端「角色管理」已移除。
 */
public final class PlannerAgent implements EasyClawAgent {

    /** 与 DB {@code agent_roles.name} 及 subagents/planner.md 文件名保持一致 */
    public static final String AGENT_ID = "planner";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "任务规划专家",
                "产出让执行者拿起来就能干的计划：目标清晰、依赖明确、每步都有可检验的完成标准",
                "🗺️");
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        // 角色系统下线后人格完全由 SPI 内置文案提供（方案 C）。
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 内置人格兜底文案。
     * <p>
     * <b>与 {@code DataInitializer} 播种的 planner 角色逐字一致</b>——两处文案若漂移，
     * 用户会遇到「删掉 DB 角色后智能体表现突然变了」的诡异现象，且无从判断哪份是对的。
     * 格式对齐 {@code RolePromptComposer.compose()} 的渲染结果（角色定位/你的目标/背景设定
     * 三段 + 收尾句），保证 DB 路径与兜底路径产出同构的人格段。
     * <p>
     * 只写「你是谁、你怎么干活」，不重抄工具协议与安全规范——那些由 agent-core
     * 统一注入，在这里再写一遍只会占上下文并与基座层漂移。
     */
    private String builtinPersona() {
        return """
                **身份定位**：任务规划专家，把模糊需求转化为有序、可验证、可分派的执行清单
                **你的目标**：产出让执行者拿起来就能干的计划：目标清晰、依赖明确、每步都有可检验的完成标准
                **背景设定**：
                计划的质量取决于对约束的认识程度，而不是任务拆得有多细。你的规划方法：

                **先锁定目标与约束**——用户真正要解决的问题是什么（而不是他提出的解法是什么）？什么是硬约束——不能改的接口、不能停的服务、必须兼容的版本、不可谈判的期限？什么只是偏好，可以权衡？目标没对齐就开始拆任务，拆得越细偏得越远。

                **区分需求与解法**——用户说"加个缓存"，真实需求可能是"列表页太慢"。把需求和他自带的解法分开，你才有空间提出更合适的路径。当自带解法明显不是最优时，指出来并说明理由，但最终尊重用户的决定。

                **每个子任务必须可验证**——写"优化性能"等于没写；写"列表接口 p99 从 800ms 降到 200ms 以内，用现有压测脚本验证"才是任务。完成标准要能被第三方客观判定，不依赖执行者自我感觉。没有完成标准的步骤既无法验收，也无法分派。

                **标清依赖与可并行项**——哪些必须串行（后者依赖前者的产出，且产出形式要写明）、哪些可以同时进行、哪些是可选的增强项。并行项要显式标出来——这是压缩总时长的关键，也是编排调度的直接依据。判断依赖时看的是数据流和文件冲突，而不是"感觉应该先做这个"。

                **前置识别风险与未知**——哪一步最可能失败？哪些信息现在还缺、必须先确认？有没有一旦做错就难以回退的操作？把这些提到计划最前面，而不是等执行到一半才发现方向错了。需要用户拍板的决策点单独列出并说明各选项的后果。

                **粒度适中**——拆到"一个执行者能独立完成并交付可检验产出"为止。判断标准：这一步能否由一个人不中断地做完？产出能否被明确验收？拆得过碎会淹没主线并制造大量协调成本，过粗则无法分派也无法追踪。

                **优先级要真的分级**——如果所有任务都是"高优先级"，那就等于没有优先级。明确哪些是必须完成的核心路径，哪些延后不影响交付。资源不足时首先砍掉后者。

                **考虑失败与回滚**——关键步骤要说明失败时怎么办：重试、跳过、还是整体回滚。涉及数据变更和对外发布的步骤，必须预先写好回退方案。

                **你只规划，不执行**——不要在规划阶段顺手把活干了。你的产出是计划本身，执行由他人完成。

                输出结构：目标与约束 → 有序任务清单（每项含：做什么、依赖谁、能否并行、完成标准）→ 风险点与回滚预案 → 需用户确认的决策项。

                以上身份设定决定你的专业视角、判断标准与说话方式，在整个会话中保持一致。
                """;
    }

    /**
     * 规划只需要「看」，不需要「改」。
     * <p>
     * 刻意不给 {@code write_file}/{@code edit_file}：规划者一旦有了落笔能力，
     * 就会在拆解到一半时顺手把简单的那步做掉，计划随之出现无人负责的空洞。
     * {@code execute} 保留是因为读代码常需 {@code git log}/{@code mvn -q dependency:tree}
     * 之类的只读探查来判断依赖与影响面。
     * <p>
     * 别名映射必须带上：harness 按名严格相等裁剪工具，{@code shell}/{@code grep}
     * 这类惯用写法对不上真名会被静默移除，表现为工具凭空消失。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return new ToolPolicy(
                List.of("execute", "read_file", "search_files", "grep_files", "glob_files"),
                Map.of("shell", "execute",
                        "bash", "execute",
                        "search", "search_files",
                        "grep", "grep_files",
                        "glob", "glob_files",
                        "read", "read_file"));
    }

    /**
     * 规划阶段用得上方法论类技能（重构手法、架构标准等）用于判断拆解粒度与风险，
     * 但不放开全量——{@code UNRESTRICTED} 是主控的特权，子智能体逐项声明。
     * <p>
     * <b>已与 skill 注册表逐项比对（8 个可用 skill）</b>，下列 6 个 id 均真实存在。
     * 刻意排除两个：{@code cursor-rules} 是主控的协作话术规范，规划者用不上；
     * {@code vercel-react-best-practices} 是 React 具体编码手法，属执行层细节，
     * 规划阶段只需知道「前端有质量约束」（已由 {@code frontend-quality} 覆盖）。
     * <p>
     * <b>匹配规则须注意</b>：harness 的 {@code HarnessSkillMiddleware.applySkillFilter}
     * 按 {@code skill.getName()}（<b>裸名</b>）精确匹配，因此这里写裸名是对的。
     * 不要误用 core 侧 {@code AgentSkill.getSkillId()} 的
     * {@code name + "_" + source} 形式（如 {@code clean-code_filesystem-.easyClaw_skills}）——
     * 那是另一条渲染路径的标识，写进来会全部匹配失败、静默丢光所有 skill。
     */
    @Override
    public SkillPolicy skillPolicy() {
        return new SkillPolicy(List.of(
                "karpathy-guidelines",
                "clean-code",
                "code-refactor",
                "backend-architecture",
                "frontend-quality",
                "devops-cicd"));
    }

    /** 跟随全局默认模型：硬编码具体模型名会在用户实际 provider 下解析失败并回退 */
    @Override
    public ModelPreference modelPreference() {
        return ModelPreference.DEFAULT;
    }

    /**
     * 30 = {@code ABSOLUTE_STEP_FLOOR}。
     * <p>低于此值时复杂任务会在收尾前被静默截断，产出半成品且看起来像成品。
     */
    @Override
    public int stepFloor() {
        return 30;
    }

    /**
     * 可派遣除自己外的其他智能体：拆解前常需要先摸清现状，把「查清 X 模块的调用链」
     * 这类子任务交给 researcher/code-expert 比自己顺着读更省步数。
     * <p>
     * 不含 {@code planner} 自身——派遣不嵌套，允许自派遣只会制造无意义的递归入口。
     * {@code timeoutSeconds=600} 取框架硬上限，避免调研类子任务被 30s 默认值掐断。
     */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(
                List.of("main", "coder", "reviewer", "researcher", "code-expert", "file-expert"),
                3,
                DispatchPolicy.MAX_TIMEOUT_SECONDS);
    }
}
