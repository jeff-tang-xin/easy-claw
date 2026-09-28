package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.memory.settings.MemorySettingsEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceAgentBuilder#shouldEnableMemoryHooks} 判定测试：
 * 主 Agent 恒启用记忆提取；子 Agent 仅当用户开启 subagentFlushEnabled——
 * 默认关闭，子的沉淀经黑板归口主 Agent 统一写记忆。
 */
class WorkspaceAgentBuilderMemoryTest {

    private MemorySettingsEntity settingsWith(Boolean subagentFlushEnabled) {
        return MemorySettingsEntity.builder()
                .userId("local")
                .subagentFlushEnabled(subagentFlushEnabled)
                .build();
    }

    @Test
    void 主Agent无视开关恒启用记忆hooks() {
        assertTrue(WorkspaceAgentBuilder.shouldEnableMemoryHooks("main", settingsWith(false)));
        assertTrue(WorkspaceAgentBuilder.shouldEnableMemoryHooks("main", settingsWith(true)));
        assertTrue(WorkspaceAgentBuilder.shouldEnableMemoryHooks("main", settingsWith(null)));
    }

    @Test
    void 子Agent默认关闭记忆hooks() {
        assertFalse(WorkspaceAgentBuilder.shouldEnableMemoryHooks("coder", settingsWith(false)));
        assertFalse(WorkspaceAgentBuilder.shouldEnableMemoryHooks("coder", settingsWith(null)));
    }

    @Test
    void 子Agent开启开关后启用记忆hooks() {
        assertTrue(WorkspaceAgentBuilder.shouldEnableMemoryHooks("coder", settingsWith(true)));
    }

    @Test
    void 未知或空agentId按子Agent处理() {
        assertFalse(WorkspaceAgentBuilder.shouldEnableMemoryHooks("nonexistent", settingsWith(false)));
        assertFalse(WorkspaceAgentBuilder.shouldEnableMemoryHooks(null, settingsWith(false)));
    }
}
