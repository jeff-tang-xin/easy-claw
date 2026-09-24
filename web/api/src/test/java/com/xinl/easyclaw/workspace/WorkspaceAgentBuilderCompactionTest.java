package com.xinl.easyclaw.workspace;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WorkspaceAgentBuilder} 窗口感知压缩触发阈值测试：
 * 按模型名在 {@code ModelContextWindows} 查窗口，触发点 = clamp(窗口 - 50K 预留, 100K, 300K)；
 * 查不到（cloud 别名/未收录模型）返回 0，由 buildCompactionConfig 回落配置值（默认 100K）。
 */
class WorkspaceAgentBuilderCompactionTest {

    private Model model(String name) {
        return OpenAIChatModel.builder()
                .baseUrl("http://localhost:1/v1")
                .apiKey("test-key")
                .modelName(name)
                .build();
    }

    // ---- lookupContextWindow：窗口表匹配 ----

    @Test
    void 窗口表命中常见模型() {
        assertEquals(128_000, WorkspaceAgentBuilder.lookupContextWindow("gpt-4o"));
        assertEquals(200_000, WorkspaceAgentBuilder.lookupContextWindow("glm-4.6"));
        assertEquals(1_048_576, WorkspaceAgentBuilder.lookupContextWindow("kimi-k3"));
        assertEquals(1_000_000, WorkspaceAgentBuilder.lookupContextWindow("qwen-turbo"));
    }

    @Test
    void 窗口表剥离provider前缀() {
        assertEquals(128_000, WorkspaceAgentBuilder.lookupContextWindow("openai:gpt-4o"));
        assertEquals(200_000, WorkspaceAgentBuilder.lookupContextWindow("zhipu:glm-4.6"));
    }

    @Test
    void 窗口表未收录或空名返回0() {
        assertEquals(0, WorkspaceAgentBuilder.lookupContextWindow("unknown-model-xyz"));
        assertEquals(0, WorkspaceAgentBuilder.lookupContextWindow(""));
        assertEquals(0, WorkspaceAgentBuilder.lookupContextWindow("  "));
    }

    // ---- resolveWindowAwareTriggerTokens：clamp 语义 ----

    @Test
    void 窗口128K模型夹到下限100K() {
        // 128K - 50K = 78K < 下限 100K → 100K（与历史默认一致，128K 模型行为不变）
        assertEquals(100_000, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("gpt-4o")));
    }

    @Test
    void 窗口200K模型按窗口减预留() {
        // 200K - 50K = 150K，落在 [100K, 300K] 内
        assertEquals(150_000, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("glm-4.6")));
        assertEquals(150_000, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("claude-sonnet-4")));
    }

    @Test
    void 超大窗口模型夹到上限300K() {
        assertEquals(300_000, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("kimi-k3")));
        assertEquals(300_000, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("qwen-turbo")));
    }

    @Test
    void 未知模型或null回落0() {
        assertEquals(0, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(model("unknown-model-xyz")));
        assertEquals(0, WorkspaceAgentBuilder.resolveWindowAwareTriggerTokens(null));
    }
}
