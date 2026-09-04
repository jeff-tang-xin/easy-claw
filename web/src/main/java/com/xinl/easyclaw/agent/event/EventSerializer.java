package com.xinl.easyclaw.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xinl.easyclaw.agent.domain.StreamEvent;

/**
 * 把单个 {@link StreamEvent} 序列化为推给前端的 JSON 报文（含会话信封）。
 *
 * <p>为什么需要这层抽象：事件的「线上形状」是后端与已上线前端之间的协议，而目前它由
 * {@code ChatWebSocketHandler.sendJson} 里的一段内联 {@code LinkedHashMap} + {@code writeValueAsString}
 * 隐式定义——协议既没有名字，也没有可执行的规格。后续引入 v2 原生事件协议时，
 * 正确的做法是在本接口旁并列一个 {@code NativeEventSerializer} 实现，由会话按
 * 协商到的协议版本选择实现；错误的做法是在推送调用点加 {@code if (v2)} 分支——
 * 那会让两套形状的判定散落到每一个出口，且无法对「两套形状是否等价」写测试。
 *
 * <p>实现约定：无状态、线程安全（多个并发会话共享同一实例），且不得对入参做任何裁剪或
 * 兜底改写——信封之外的字段形状完全由 {@link StreamEvent} 自身的 Jackson 注解决定。
 *
 * @see LegacyEventSerializer 当前线上（legacy）协议的实现，其测试即该协议的可执行规格
 */
public interface EventSerializer {

    /**
     * 序列化一个事件。
     *
     * <p>序列化失败以 {@link JsonProcessingException} 抛出而非内部吞掉返回 null：
     * 「这条事件发不出去」需要带上调用点的会话上下文才能定位，而调用点才知道该记什么日志、
     * 是丢弃这一条还是终止整条流。
     *
     * @param workspaceId 事件所属工作区 id（未知时为空串，不得为 null）
     * @param sessionId   事件所属会话 id
     * @param event       待序列化事件
     * @return 可直接写入 WS 文本帧的 JSON 字符串
     * @throws JsonProcessingException 序列化失败
     */
    String serialize(String workspaceId, String sessionId, StreamEvent event) throws JsonProcessingException;
}
