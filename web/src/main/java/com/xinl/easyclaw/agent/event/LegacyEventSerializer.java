package com.xinl.easyclaw.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.agent.domain.StreamEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前线上（legacy）事件协议的序列化实现：{@code {"workspaceId":..,"sessionId":..,"event":{..}}}。
 *
 * <p>从 {@code ChatWebSocketHandler.sendJson} 迁出的动机：这段信封逻辑是后端与已上线前端之间
 * 唯一的事件协议定义，混在 WS 推送方法里既无法单独测试，也让「换协议」变成改推送代码。
 * 迁出后协议形状由 {@code LegacyEventSerializerTest} 逐 type 断言精确 JSON 固定下来，
 * 成为后续 v2 协议证明等价（或明确声明差异）的唯一凭据。
 *
 * <p>信封用 {@link LinkedHashMap} 而非 {@code Map.of}：键顺序 workspaceId → sessionId → event
 * 是已落盘/已抓包的报文形状，虽然 JSON 对象在语义上无序，但保序能让日志与转录 diff 稳定，
 * 也避免「只是换了个 Map 实现」这种看不出风险的改动悄悄改变输出字节。
 *
 * <p>事件体本身不做任何加工：{@code toolCallId} / {@code subId} 为 null 时字段整体消失，
 * 由 {@link StreamEvent} 上的 {@code @JsonInclude(NON_NULL)} 保证——前端的降级逻辑
 * （无 toolCallId 退回就近匹配、无 subId 退回按名归并）依赖的正是「字段不存在」而非「字段为 null」。
 *
 * <p>无状态、线程安全：{@link ObjectMapper} 在配置完成后即可并发使用。
 */
public final class LegacyEventSerializer implements EventSerializer {

    private final ObjectMapper mapper;

    public LegacyEventSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String serialize(String workspaceId, String sessionId, StreamEvent event) throws JsonProcessingException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("workspaceId", workspaceId);
        envelope.put("sessionId", sessionId);
        envelope.put("event", event);
        return mapper.writeValueAsString(envelope);
    }
}
