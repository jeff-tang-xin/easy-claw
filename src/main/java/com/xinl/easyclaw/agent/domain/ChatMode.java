package com.xinl.easyclaw.agent.domain;

/**
 * 对话模式：纯二选一，决定对话基调 + AgentScope Plan Mode 开关。
 * <ul>
 *   <li><b>PLAN（计划）</b>：启用原生 Plan Mode，深度推理 + 先规划后执行。
 *       DEEP 语义内收在此模式中——慢思考、分步分析、复杂任务自动进入规划流程。</li>
 *   <li><b>QUICK（自由）</b>：关闭 Plan Mode，简短直接回答，不走规划。</li>
 * </ul>
 * <p>Skill 是独立的上下文注入（.md 文件），不属于模式层；
 * 工具可用性由工具管理页的 enable/disable 控制，不用 prompt 干预。</p>
 */
public final class ChatMode {

    private ChatMode() {}

    public enum BaseMode {
        /** 计划模式：Plan Mode 原生开启 + 深度推理（DEEP 语义内收于此） */
        PLAN("📋", "计划", """
                —— 计划模式（Plan Mode 已启用 + 深度推理）——
                1. 面对复杂任务时，先用 plan_enter 开启规划，
                   用 plan_write 分步骤记录计划，规划完成后 plan_exit 再逐步执行。
                2. 推理过程慢思考、逐步拆解：列出假设 → 收集证据 → 得出结论，
                   必要时派生子 Agent 深入调查。
                3. 简单问题可直接回答，不需要强制规划。
                4. 所有操作限定在当前 workspace 目录内。
                """),

        /** 自由模式：关闭 Plan Mode，简短直接，不走规划 */
        QUICK("⚡", "自由", """
                —— 自由模式 ——
                简短直接地回答，不做规划，不做长篇展开。
                能用已有知识回答就直接回答；仅当用户明确要求时才调用工具。
                """);

        public final String emoji;
        public final String label;
        public final String instruction;

        BaseMode(String emoji, String label, String instruction) {
            this.emoji = emoji;
            this.label = label;
            this.instruction = instruction;
        }

        public static BaseMode parse(String raw) {
            if (raw == null || raw.isBlank()) return PLAN;
            try {
                return BaseMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return PLAN;
            }
        }
    }

    public static String buildInstruction(BaseMode base) {
        return (base == null ? BaseMode.PLAN : base).instruction.trim();
    }
}
