package com.xinl.easyclaw.agent.filexpert;

import com.xinl.easyclaw.base.agent.AgentContext;
import com.xinl.easyclaw.base.agent.AgentProfile;
import com.xinl.easyclaw.base.agent.DispatchPolicy;
import com.xinl.easyclaw.base.agent.EasyClawAgent;
import com.xinl.easyclaw.base.agent.ModelPreference;
import com.xinl.easyclaw.base.agent.PromptContribution;
import com.xinl.easyclaw.base.agent.SkillPolicy;
import com.xinl.easyclaw.base.agent.ToolPolicy;

import java.util.List;

/**
 * 文件操作专家智能体。
 * <p>
 * 文件系统运维专家，擅长批量处理、精确定位与结构分析，是主控智能体的「手和眼」。
 * <p>
 * <b>人格来源分层</b>（与 {@code MainAgent} 一致）：DB 有记录时以 DB 为准，
 * 无记录时用本类内置文案兜底。
 */
public final class FileExpertAgent implements EasyClawAgent {

    /** 与 DB {@code agent_roles.name} 及 {@code subagents/file-expert.md} 文件名一致 */
    public static final String AGENT_ID = "file-expert";

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public AgentProfile profile() {
        return new AgentProfile(
                "文件操作专家",
                "文件系统运维专家，擅长批量处理、精确定位与结构分析",
                "📁");
    }

    @Override
    public PromptContribution prompt(AgentContext ctx) {
        // 角色系统下线后人格完全由 SPI 内置文案提供（方案 C）。
        return PromptContribution.ofPersona(builtinPersona());
    }

    /**
     * 内置人格兜底文案。
     * <p>与 {@code DataInitializer} 播种的 file-expert 角色逐字一致：搬运而非重写。
     */
    private String builtinPersona() {
        return """
                **身份定位**：文件系统运维专家，擅长批量处理、精确定位与结构分析
                **目标**：准确、可预期地完成文件与目录操作，任何破坏性动作发生前都让用户清楚影响范围

                你处理的是用户不可再生的资产。一次误删、一次覆盖写、一次编码转换失误，损失往往无法挽回，所以你的操作习惯是：

                **先勘察，后动手**——批量操作前一定先用只读方式列出会被命中的完整文件清单，确认范围与预期一致再执行。命中数量异常是最强的警报信号：预计改 5 个文件却匹配到 300 个，说明模式写错了，此时唯一正确的动作是停下来重新确认，而不是"先跑跑看"。

                **破坏性操作需要明示授权**——删除、覆盖已有文件、就地批量替换、移动或重命名目录结构，执行前说清楚三件事：影响哪些文件、改动是什么、能否撤销。得到确认再动手。

                **保持文件原貌**——编辑时严格保留原有的缩进风格（空格还是 Tab、几个空格）、换行符（CRLF 还是 LF）和字符编码。不要顺手格式化整个文件，不要引入 BOM，不要修改无关行的尾随空白。这些改动会让 diff 里出现成百上千行噪声，真正的改动淹没其中，评审形同虚设。跨平台项目尤其注意换行符：在 Windows 上编辑 LF 文件时很容易整个文件被转成 CRLF。

                **优先精确匹配而非正则批改**——面对源代码时，正则替换极易误伤：字符串字面量里的同名文本、注释中的示例、命名相似但语义不同的符号。能用精确定位逐处修改就不要图省事写一条正则扫全库。确实需要批量时，先在只读模式下输出所有将被修改的行供确认。

                **大文件分页读**——不要试图把大文件一次性塞进上下文。先用搜索定位到目标区域，再按偏移分段读取。盲目全量读取会挤占上下文，让后续判断质量下降。

                **搜索要先宽后窄**——定位不明确时先用宽松模式看命中分布，再逐步收紧条件。一上来就写精确到极致的模式，往往因为一个字符不匹配而零命中，反而误判为"不存在"。

                **警惕路径与编码陷阱**——路径中的空格与中文要正确引用；不同平台的路径分隔符差异；文件名大小写在 Windows 上不敏感而在 Linux 上敏感。这些细节导致的失败往往表现为莫名其妙的"文件不存在"。

                **如实报告**——操作后说明实际影响了多少文件、有无跳过项和失败项。部分成功是最危险的状态，必须明确列出哪些成功、哪些失败、当前处于什么中间态，绝不用"已完成"一笔带过。
                """;
    }

    /**
     * 工具白名单与 {@code subagents/file-expert.md} 的 {@code tools:} frontmatter 一致。
     * <p>
     * 注意用 {@code list_files} 而非 {@code list_directory}：前者是 harness 的真实注册名，
     * 后者虽也在 {@code KNOWN_TOOL_NAMES} 里，但 {@code .md} 声明与既有行为都用 list_files。
     */
    @Override
    public ToolPolicy toolPolicy() {
        return ToolPolicy.of(
                "execute", "read_file", "write_file", "edit_file",
                "list_files", "grep_files", "glob_files", "search_files");
    }

    @Override
    public SkillPolicy skillPolicy() {
        return SkillPolicy.UNRESTRICTED;
    }

    @Override
    public ModelPreference modelPreference() {
        return ModelPreference.DEFAULT;
    }

    @Override
    public int stepFloor() {
        return 30;
    }

    /** 可派遣除自己以外的其他智能体 */
    @Override
    public DispatchPolicy dispatchPolicy() {
        return new DispatchPolicy(
                List.of("main", "coder", "reviewer", "planner", "researcher", "code-expert"),
                3, DispatchPolicy.MAX_TIMEOUT_SECONDS);
    }
}
