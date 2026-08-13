package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.AgentProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AgentProcess 事件桥接
 * <p>
 * 职责：监听 Embabel AgentPlatform 事件体系（AgentProcessPausedEvent /
 * AgentProcessCompletedEvent / AgentProcessFailedEvent 等），转换为业务层
 * StreamEvent 推送到前端 WebSocket。
 * <p>
 * 设计：每个 AgentProcess 都有唯一 id，按 sessionId 注册监听器。
 */
@Component
public class AgentProcessEventBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessEventBridge.class);

    private final ApplicationEventPublisher publisher;

    private final Map<String, SessionListener> listeners = new ConcurrentHashMap<>();

    public AgentProcessEventBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void register(String sessionId, Consumer<ProcessLifecycleEvent> handler) {
        listeners.put(sessionId, new SessionListener(sessionId, handler));
        log.debug("注册事件监听器: sessionId={}", sessionId);
    }

    public void unregister(String sessionId) {
        listeners.remove(sessionId);
        log.debug("注销事件监听器: sessionId={}", sessionId);
    }

    @EventListener
    public void onProcessPaused(AgentProcessPausedEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.info("AgentProcess 暂停: id={}, status={}", process.getId(), process.statusReport());
        publish(process, ProcessLifecycleEvent.Type.PAUSED, null);
    }

    @EventListener
    public void onProcessWaiting(AgentProcessWaitingEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.info("AgentProcess 等待输入: id={}", process.getId());
        publish(process, ProcessLifecycleEvent.Type.WAITING, null);
    }

    @EventListener
    public void onProcessCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.info("AgentProcess 完成: id={}, cost={}", process.getId(), process.totalCost());
        publish(process, ProcessLifecycleEvent.Type.COMPLETED, event.getResult());
    }

    @EventListener
    public void onProcessFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.error("AgentProcess 失败: id={}, error={}", process.getId(), process.getFailureInfo());
        publish(process, ProcessLifecycleEvent.Type.FAILED, process.getFailureInfo());
    }

    @EventListener
    public void onProcessPlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        log.debug("AgentProcess 计划制定: id={}", process.getId());
        publish(process, ProcessLifecycleEvent.Type.PLAN_FORMULATED, null);
    }

    private void publish(AgentProcess process, ProcessLifecycleEvent.Type type, Object data) {
        ProcessLifecycleEvent evt = new ProcessLifecycleEvent(process.getId(), type, data);
        SessionListener listener = listeners.get(process.getId());
        if (listener != null) {
            try {
                listener.handler.accept(evt);
            } catch (Exception e) {
                log.error("推送生命周期事件失败: sessionId={}, err={}", process.getId(), e.getMessage());
            }
        } else {
            publisher.publishEvent(evt);
        }
    }

    private record SessionListener(String sessionId, Consumer<ProcessLifecycleEvent> handler) {}
}
