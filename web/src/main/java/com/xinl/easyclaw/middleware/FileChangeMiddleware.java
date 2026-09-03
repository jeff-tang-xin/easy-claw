package com.xinl.easyclaw.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 写类工具的文件变更检测 —— <b>影子模式</b>，当前不推送任何前端事件。
 *
 * <p><b>为什么是影子模式</b>：{@code AgentService.emitFileChangedIfWriteTool} 正在生产环境
 * 承担 {@code file_changed} 推送。若本 middleware 同时推送，前端会收到双份事件并重复刷新
 * 文件树。重构方案（{@code docs/refactor-plan.md:391}）规定的降险策略是「保留旧逻辑并行
 * 运行，日志对比新旧路径输出」——所以这里只算出结果写 DEBUG 日志，供与旧路径比对。
 *
 * <p><b>切换时机</b>：Phase 5 删除翻译层时，把 {@link #SHADOW_MODE} 置为 false 并同步摘除
 * {@code AgentService} 的旧实现，两个动作必须在同一个 commit 里完成，否则就会出现
 * 「双推」或「不推」。
 *
 * <p><b>为什么挂 onActing</b>：{@code MiddlewareBase} 只有 5 个钩子，没有 onToolCall/
 * onToolResult。工具相关的横切逻辑只能挂在 onActing 上，从 {@link ActingInput#toolCalls()}
 * 读取本轮要执行的工具。
 */
public class FileChangeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(FileChangeMiddleware.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 影子模式开关。为 true 时只记日志不发事件。
     * <p>
     * 定义成常量而非配置项，是因为它与「AgentService 旧实现是否还在」严格绑定：
     * 做成可运行时切换只会让两者失配的窗口变大。
     */
    private static final boolean SHADOW_MODE = true;

    /**
     * 只列出「确定会写文件」的工具。与 {@code AgentService.FILE_WRITE_TOOLS} 保持一致——
     * 影子比对期间两边必须同源，否则日志差异反映的是清单不同步而非迁移缺陷。
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

        // 【只认成功的工具】旧实现 AgentService:2003 在 state != ERROR 分支才推 file_changed。
        // 用 doOnComplete 是错的：next 流正常结束不代表工具成功，失败的工具同样会走完流程，
        // 那样会在写文件失败时也通知前端刷新。必须逐个观察 ToolResultEndEvent 的 state。
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
                    if (SHADOW_MODE) {
                        log.debug("[shadow] file_changed: session={}, tool={}, path='{}'",
                                ctx.getSessionId(), end.getToolCallName(), path);
                    }
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
