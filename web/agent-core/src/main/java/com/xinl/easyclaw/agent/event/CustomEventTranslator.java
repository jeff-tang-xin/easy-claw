package com.xinl.easyclaw.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.middleware.FileChangeMiddleware;
import com.xinl.easyclaw.middleware.ToolFailGuard;
import io.agentscope.core.event.CustomEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 把应用层 middleware 发出的 {@link CustomEvent} 翻译为前端协议的 {@link StreamEvent}。
 *
 * <p>框架的 core 事件（TEXT_BLOCK_DELTA 等）形状由 AgentScope 定义，而应用层自造的扩展事件
 * （文件变更、工具失败护栏）统一走 {@code CustomEvent(name, value)}，避免污染 core 事件枚举。
 * 本类是这两类事件中「应用层那一半」的唯一翻译出口。
 *
 * <p>从 {@code AgentService} 迁出的动机（Phase 3b）：翻译逻辑与会话状态无关——只依赖入参事件
 * 与一个 {@link ObjectMapper}——留在 {@code AgentService} 里既拉长了那个已近 1900 行的类，
 * 也让「协议翻译」与「会话编排」两件事纠缠在一起。独立成类后，未来切换事件协议（v2）时
 * 只需替换本类或在其旁增设新实现，{@code AgentService} 无需改动。
 *
 * <p>无状态、线程安全：可安全地被多个并发会话共享同一实例。
 */
public final class CustomEventTranslator {

    private static final Logger log = LoggerFactory.getLogger(CustomEventTranslator.class);

    private final ObjectMapper mapper;

    public CustomEventTranslator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 翻译单个 {@link CustomEvent} 并把结果投递给 {@code onEvent}。
     *
     * <p>只处理已知 name。框架的 {@code CustomEvent} Javadoc 明确要求消费端对未知 name
     * 静默跳过（这是协议演进的兼容策略：新版 middleware 发的事件不能让旧消费端报错），
     * 所以未知分支不记日志、不抛异常。
     *
     * @param event   middleware 发出的自定义事件
     * @param onEvent 前端事件出口
     */
    public void translate(CustomEvent event, Consumer<StreamEvent> onEvent) {
        if (event == null) {
            return;
        }
        String name = event.getName();
        if (name == null) {
            return;
        }
        Map<String, Object> value = event.getValue();
        switch (name) {
            case FileChangeMiddleware.EVENT_NAME -> {
                Object path = value == null ? null : value.get("path");
                // 空串是有效载荷（shell 类工具「有变更但位置未知」），不能当无效值过滤
                onEvent.accept(StreamEvent.fileChanged(path == null ? "" : path.toString()));
            }
            case ToolFailGuard.EVENT_NAME -> {
                // 保持与旧实现完全一致的 JSON 形状：前端按 type 字段路由，
                // 改字段名会让已上线的前端静默丢弃该提示。
                try {
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("type", ToolFailGuard.EVENT_NAME);
                    payload.put("tool", String.valueOf(value == null ? null : value.get("tool")));
                    Object fails = value == null ? null : value.get("fails");
                    payload.put("fails", fails instanceof Number n ? n.intValue() : 0);
                    payload.put("message", String.valueOf(value == null ? null : value.get("message")));
                    onEvent.accept(StreamEvent.context(mapper.writeValueAsString(payload)));
                } catch (JsonProcessingException ex) {
                    log.debug("tool_fail_guard 事件序列化失败，跳过: {}", ex.getMessage());
                }
            }
            default -> {
                // 未知 name：按框架约定静默跳过
            }
        }
    }
}
