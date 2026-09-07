package com.xinl.easyclaw.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 写类工具的文件变更检测 —— 通过 {@link AgentEventEmitter} 推送 {@code file_changed}。
 *
 * <p><b>本类是 {@code file_changed} 的唯一发射方</b>。原实现
 * {@code AgentService.emitFileChangedIfWriteTool} 已在同一提交中删除，不存在双推。
 *
 * <p><b>为什么必须用 AgentEventEmitter 而不是往返回流里拼事件</b>：
 * {@code ReActAgent:2760} 对 onActing 链的返回流做的是
 * {@code stream.doOnNext(识别RequestStopEvent).then(...)} —— {@code then()} 会丢弃所有元素。
 * 在返回的 Flux 上 concat 新事件，事件不会到达订阅端，且单测里流是通的、看起来完全正常，
 * 属于静默失效。真正的出口是核心自己调用的 {@code publishEvent}（{@code ReActAgent:2896}），
 * 而 {@link AgentEventEmitter} 正是框架为「从执行链内部注入事件」公开的入口，
 * 由 {@code ReActAgent:1078} 放入 Reactor Context。
 *
 * <p><b>为什么挂 onActing</b>：{@code MiddlewareBase} 只有 5 个钩子，没有 onToolCall/
 * onToolResult。工具相关的横切逻辑只能挂在 onActing 上，从 {@link ActingInput#toolCalls()}
 * 读取本轮要执行的工具。
 */
public class FileChangeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(FileChangeMiddleware.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CustomEvent 的 name，前端据此路由。与旧 StreamEvent.fileChanged 的语义一致。 */
    public static final String EVENT_NAME = "file_changed";

    /**
     * 只列出「确定会写文件」的工具。清单迁移自 {@code AgentService.FILE_WRITE_TOOLS}。
     */
    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file");

    /**
     * 会改文件但拿不到确切路径的工具。shell 能做任何事（git checkout、del、重定向），
     * 命令行无法可靠解析受影响路径，故只发「位置未知」信号，由前端按自身上下文决定重拉。
     */
    private static final Set<String> OPAQUE_WRITE_TOOLS = Set.of("execute");

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {

        List<ToolUseBlock> writeCalls = collectWriteCalls(input);
        if (writeCalls.isEmpty()) {
            return next.apply(input);
        }

        // deferContextual 而非直接 next.apply：AgentEventEmitter 存放在 Reactor Context 里，
        // 只有订阅时才能拿到。emitter 缺席是正常情况（非流式 call() 路径没有它），
        // 此时静默跳过——文件树刷新是 UI 增强，不能让它影响工具执行。
        return Flux.deferContextual(cv -> {
            AgentEventEmitter emitter = AgentEventEmitter.fromContext(cv).orElse(null);

            // 【只认成功的工具】旧实现在 state != ERROR 分支才推 file_changed。
            // 用 doOnComplete 是错的：next 流正常结束不代表工具成功，失败的工具同样会走完
            // 流程，那样会在写文件失败时也通知前端刷新。必须逐个观察 ToolResultEndEvent。
            return next.apply(input)
                    .doOnNext(evt -> {
                        if (!(evt instanceof ToolResultEndEvent end)) {
                            return;
                        }
                        if (end.getState() == ToolResultState.ERROR) {
                            return;
                        }
                        String path = resolveChangedPath(writeCalls, end.getToolCallId());
                        if (path == null) {
                            return;
                        }
                        if (emitter == null) {
                            log.debug("file_changed 跳过（当前调用链无 AgentEventEmitter）: path='{}'",
                                    path);
                            return;
                        }
                        emitter.emit(new CustomEvent(EVENT_NAME, Map.of("path", path)));
                    });
        });
    }

    /** 挑出本轮中属于写类（含路径不可知类）的工具调用 */
    private List<ToolUseBlock> collectWriteCalls(ActingInput input) {
        if (input == null || input.toolCalls() == null) {
            return List.of();
        }
        List<ToolUseBlock> calls = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (isWriteTool(call.getName())) {
                calls.add(call);
            }
        }
        return calls;
    }

    private boolean isWriteTool(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return FILE_WRITE_TOOLS.contains(normalized) || OPAQUE_WRITE_TOOLS.contains(normalized);
    }

    /**
     * 按 toolCallId 定位发起该结果的工具调用，取出受影响路径。
     *
     * <p>返回 {@code null} 表示「这个结果与文件变更无关」；返回空串是<b>有意义的信号</b>，
     * 代表「有文件变了但位置未知」（shell 类工具），前端据此刷新当前关注的位置——
     * 不要因为它是空串就当成无效值过滤掉。
     */
    private String resolveChangedPath(List<ToolUseBlock> writeCalls, String toolCallId) {
        for (ToolUseBlock call : writeCalls) {
            if (!java.util.Objects.equals(call.getId(), toolCallId)) {
                continue;
            }
            String name = call.getName().toLowerCase(Locale.ROOT);
            if (OPAQUE_WRITE_TOOLS.contains(name)) {
                return "";
            }
            String path = readPathArg(call);
            // 统一为正斜杠：Windows 下工具入参可能是反斜杠，前端按字符串比对路径，
            // 不归一化会导致同一文件被当成两个。
            return path.isBlank() ? null : path.replace('\\', '/');
        }
        return null;
    }

    /**
     * 读取工具入参里的 {@code path} 字段。
     *
     * <p>入参可能因流式累积而不是合法 JSON（见 ToolCallsAccumulator 的静默降级），
     * 此时返回空串跳过——文件刷新是增强能力，不能因解析失败影响主对话流程。
     */
    private String readPathArg(ToolUseBlock call) {
        Object raw = call.getInput();
        if (raw == null) {
            return "";
        }
        try {
            JsonNode node = MAPPER.valueToTree(raw);
            return node.path("path").asText("");
        } catch (RuntimeException e) {
            log.debug("file_changed 跳过（工具入参无法解析）: tool={}", call.getName());
            return "";
        }
    }
}
