package com.xinl.easyclaw.agent.embabel;

import java.util.List;
import java.util.Map;

public final class PresetIntent {

    private PresetIntent() {}

    /** 通用助手：全量 skill 可用 */
    public static final String GENERAL = "general";

    /** 软件开发：代码分析、重构、评审、调试 */
    public static final String CODING = "coding";

    /** 周报/月报：邮件收集、整理、批注、发送 */
    public static final String WEEKLY_REPORT = "weekly-report";

    /** 内容创作：研究、大纲、写作、修改、导出 */
    public static final String CONTENT_CREATE = "content-create";

    /** 邮件分拣：收件 → 分类 → 写回复 → 确认 */
    public static final String MAIL_TRIAGE = "mail-triage";

    /** 数据分析：数据收集、清洗、可视化、报告 */
    public static final String DATA_ANALYSIS = "data-analysis";

    /** DevOps：CI/CD 管道、部署、监控 */
    public static final String DEVOPS = "devops";

    /** 每个 intent 默认激活的 skill 集 */
    public static final Map<String, List<String>> INTENT_DEFAULT_SKILLS = Map.of(
            GENERAL, List.of(
                    "karpathy-guidelines", "cursor-rules", "summarize",
                    "annotate", "send-email", "research"
            ),
            CODING, List.of(
                    "karpathy-guidelines", "cursor-rules", "code-refactor",
                    "backend-architecture", "code-review", "test-write"
            ),
            WEEKLY_REPORT, List.of(
                    "email-collect", "email-extract", "summarize",
                    "classify", "annotate", "send-email", "format-output"
            ),
            CONTENT_CREATE, List.of(
                    "research", "outline", "draft", "revise",
                    "annotate", "export-pdf", "summarize"
            ),
            MAIL_TRIAGE, List.of(
                    "email-collect", "email-extract", "classify",
                    "summarize", "annotate", "send-email"
            ),
            DATA_ANALYSIS, List.of(
                    "data-collect", "data-clean", "data-viz",
                    "summarize", "report", "annotate"
            ),
            DEVOPS, List.of(
                    "cicd-create", "deploy", "monitor", "rollback",
                    "backend-architecture"
            )
    );

    /** 每个 intent 对应的活跃 Agent 列表（排除不在该场景的 Agent） */
    public static final Map<String, List<String>> INTENT_ACTIVE_AGENTS = Map.of(
            GENERAL, List.of("orchestrator", "coding", "content", "mail", "research", "interaction"),
            CODING, List.of("orchestrator", "coding", "file", "review", "research"),
            WEEKLY_REPORT, List.of("orchestrator", "mail", "content", "interaction"),
            CONTENT_CREATE, List.of("orchestrator", "content", "research", "file", "interaction"),
            MAIL_TRIAGE, List.of("orchestrator", "mail", "content", "interaction"),
            DATA_ANALYSIS, List.of("orchestrator", "data", "research", "content", "interaction"),
            DEVOPS, List.of("orchestrator", "devops", "security", "coding", "research")
    );

    /** 预设 intent 元数据（UI 下拉框用） */
    public static final Map<String, IntentMeta> INTENT_META = Map.of(
            GENERAL, new IntentMeta("通用助手", "全场景通用，所有能力可用", "🤖"),
            CODING, new IntentMeta("软件开发", "代码分析、重构、评审、调试、测试", "💻"),
            WEEKLY_REPORT, new IntentMeta("周报/月报", "邮件收集、整理、批注、发送", "📝"),
            CONTENT_CREATE, new IntentMeta("内容创作", "研究、大纲、写作、修改、导出 PPT/PDF", "✍️"),
            MAIL_TRIAGE, new IntentMeta("邮件分拣", "收件 → 分类 → 写回复 → 确认", "📧"),
            DATA_ANALYSIS, new IntentMeta("数据分析", "数据收集、清洗、可视化、报告", "📊"),
            DEVOPS, new IntentMeta("DevOps", "CI/CD 管道、部署、监控、回滚", "🚀")
    );

    public static List<String> resolveActiveSkills(String intent, List<String> userSkills) {
        if (userSkills != null && !userSkills.isEmpty()) {
            return userSkills;
        }
        return INTENT_DEFAULT_SKILLS.getOrDefault(
                intent == null ? GENERAL : intent,
                INTENT_DEFAULT_SKILLS.get(GENERAL)
        );
    }

    public static List<String> resolveActiveAgents(String intent) {
        return INTENT_ACTIVE_AGENTS.getOrDefault(
                intent == null ? GENERAL : intent,
                INTENT_ACTIVE_AGENTS.get(GENERAL)
        );
    }

    public record IntentMeta(String displayName, String description, String emoji) {}
}
