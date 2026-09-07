package com.xinl.easyclaw.agent;

import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

/**
 * 事件处理过程中的「副作用出口」——与「事件翻译」正交的那部分职责。
 *
 * <p>背景：{@code AgentService.handleEvent} 长期同时承担两件不相关的事——
 * ①把框架 {@code AgentEvent} 翻译成前端协议的 {@code StreamEvent}；
 * ②驱动会话级业务状态（循环防护、交付登记、HITL 生命周期）。
 * 两者交织导致协议层无法独立替换：想把翻译改成 {@code EventStreamPublisher}，
 * 就会连带把副作用一起弄丢（编译通过、线上静默失效）。
 *
 * <p>本接口把②抽出来成为显式契约。<b>注意它不是为了让 handleEvent 变成纯函数</b>——
 * 这些副作用依赖 {@code switch} 分支内的局部数据（流式拼接中的工具参数、
 * 移除槽位前的 args、现场推断出的结果状态），搬到调用方后无法重建。
 * 真正的目标是让「翻译线」与「副作用线」可以各自独立演进和测试。
 *
 * <p>实现类 {@link AgentService.SessionSideEffects} 委托给 SessionRegistry 与
 * AgentService 的私有方法；测试可传入记录式实现来断言副作用确实被触发。
 */
interface SideEffectSink {

    /**
     * 子 Agent 派发工具（agent_spawn / agent_send）的参数已完整 → 判定是否为循环调度。
     *
     * <p>只在 TOOL_CALL_END 调用：更早的 TOOL_CALL_START 时参数仍在流式拼接中，
     * 取不到 agent_id，判定会退化成工具粒度。
     *
     * @param target 从工具参数解析出的子 Agent 身份（agent_id / agent_key / label）
     */
    void onSubagentDispatched(String target);

    /**
     * 子 Agent 已交付结果 → 登记交付记录。
     *
     * <p>循环防护的判据是「拿到上一次结果后仍重派」，因此必须先有交付记录，
     * 之后的派发才可能被计为串行重派。同一批并行派发（无交付记录）不计数。
     *
     * @param target 从工具参数解析出的子 Agent 身份
     */
    void onSubagentDelivered(String target);

    /**
     * 工具需要用户确认 → 登记待确认工具与确认截止时间。
     *
     * <p>截止时间是必需的：用户永不响应时由 sweepExpiredConfirmations 兜底取消，
     * 否则 SSE 连接与会话 Map 条目会永久常驻（稳定的 OOM 路径）。
     *
     * @param toolCalls 待确认的工具调用列表
     */
    void onConfirmRequired(List<ToolUseBlock> toolCalls);

    /** 什么都不做的实现，供不关心副作用的场景（如纯翻译测试）使用。 */
    SideEffectSink NOOP = new SideEffectSink() {
        @Override
        public void onSubagentDispatched(String target) {
            // no-op
        }

        @Override
        public void onSubagentDelivered(String target) {
            // no-op
        }

        @Override
        public void onConfirmRequired(List<ToolUseBlock> toolCalls) {
            // no-op
        }
    };
}
