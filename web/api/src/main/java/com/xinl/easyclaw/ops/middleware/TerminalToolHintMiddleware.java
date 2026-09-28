package com.xinl.easyclaw.ops.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 运维 P2（pull 形态）：不注入终端内容，只追加一句静态「能力提示」，
 * 告诉模型有 {@code terminal_snapshot} / {@code terminal_tail} 两个工具、
 * 什么场景该主动调用。
 *
 * <p><b>设计取舍（2026-09-28 定稿）</b>：最初版本在 onReasoning 往推理输入末尾
 * 注入终端尾部内容（push），存在三个代价——每轮无条件白烧 token（无关问题也带 8K）、
 * USER 角色易被模型误当用户发言、敏感信息无条件出网。改为 pull 主干：
 * 模型按需调用工具获取终端内容（工具返回已经
 * {@code TerminalOutputSanitizer} 脱敏），本类只解决「模型不知道自己不知道」的问题——
 * 用户说「刚才为什么报错」时，提醒模型先调工具看终端，而不是凭空猜测。
 *
 * <p>参照 agentscope 自带 {@code TaskReminderMiddleware} 的 GROUNDING 用法：
 * {@code onSystemPrompt} 只追加静态说明，不含任何动态数据，合规且几乎零成本。
 */
public class TerminalToolHintMiddleware implements MiddlewareBase {

    private static final String HINT = """

            ## 终端上下文（运维场景）
            用户可能在终端里手动执行过命令（不经你手）。你有 `terminal_snapshot` 和\
            `terminal_tail` 两个工具可以读取终端的真实输出。
            当用户提到「刚才」「报错」「日志」「失败了」「这个进程」等指代终端操作或结果，\
            或你需要了解用户手动操作的情况时，**先调用工具读取终端再回答，不要凭空猜测**。
            终端输出中的常见凭证已脱敏为 ***，但仍不要在回复中复述任何疑似凭证的内容。\
            """;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String base = currentPrompt != null ? currentPrompt : "";
        return Mono.just(base + HINT);
    }
}
