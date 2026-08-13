package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.BoxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息盒子读取器（Stub - pending Embabel migration）
 * <p>
 * 原实现依赖 {@code io.agentscope.core.message.*} 和
 * {@code io.agentscope.core.state.AgentState}，已随 AgentScope 一并移除。
 * 当前返回空列表，待 Embabel 迁移完成后恢复实际解析逻辑。
 */
public final class AgentStateBoxReader {

    private static final Logger log = LoggerFactory.getLogger(AgentStateBoxReader.class);

    private AgentStateBoxReader() {
    }

    /**
     * 从 agent_state.json 解析时序消息列表。
     * TODO: migrate to Embabel - AgentScope types removed, returns empty list for now.
     */
    public static List<BoxMessage> read(Path agentStateJson) {
        log.info("AgentStateBoxReader.read() called (no-op pending Embabel migration): {}", agentStateJson);
        return new ArrayList<>();
    }
}
