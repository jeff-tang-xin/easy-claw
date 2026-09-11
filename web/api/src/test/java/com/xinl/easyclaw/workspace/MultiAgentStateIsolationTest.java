package com.xinl.easyclaw.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinl.easyclaw.agent.SubagentLoader;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.legacy.ToolkitState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 多智能体状态隔离回归测试。
 *
 * <p><b>为什么需要这组测试</b>：{@link JsonFileAgentStateStore} 的落盘路径是
 * {@code root/<userId>/<sessionId>/<key>}，<b>不包含 agent 的 name</b>
 * （见 vendored {@code JsonFileAgentStateStore#getStatePath}）。因此当一个 workspace
 * 装配出多个 HarnessAgent 时，若它们共用同一个 state 根目录，在同一 sessionId 下会
 * <b>互相覆盖对话状态，且不抛任何异常</b>——属于最难排查的静默数据损坏。
 *
 * <p>{@code WorkspaceAgentBuilder.stateDir} 通过给非主智能体分配独立子目录来规避，
 * 同时保持主智能体目录不变以兼容存量会话。
 */
class MultiAgentStateIsolationTest {

    private static final String USER = "default";
    private static final String SESSION = "session-1";
    private static final String KEY = "toolkit";

    private static ToolkitState state(String marker) {
        return new ToolkitState(List.of(marker));
    }

    private static String markerOf(JsonFileAgentStateStore store) {
        return store.get(USER, SESSION, KEY, ToolkitState.class)
                .map(ToolkitState::activeGroups)
                .filter(groups -> !groups.isEmpty())
                .map(List::getFirst)
                .orElse(null);
    }

    /**
     * 反向验证：共用同一个 state 根目录时，后写入的智能体会覆盖前者。
     *
     * <p>这条测试证明「隔离」不是过度设计——缺陷真实存在。若哪天框架给状态路径
     * 加上了 agent 维度，本测试会失败，届时可以简化 stateDir 逻辑。
     */
    @Test
    @DisplayName("反向验证：共享 state 目录会导致智能体状态互相覆盖（静默）")
    void sharedStateDirCausesSilentOverwrite(@TempDir Path root) {
        JsonFileAgentStateStore coderStore = new JsonFileAgentStateStore(root);
        JsonFileAgentStateStore reviewerStore = new JsonFileAgentStateStore(root);

        coderStore.save(USER, SESSION, KEY, state("coder"));
        reviewerStore.save(USER, SESSION, KEY, state("reviewer"));

        assertEquals("reviewer", markerOf(coderStore),
                "共享目录下 coder 读到的应是 reviewer 写的值（证明覆盖缺陷存在）");
    }

    /** 核心断言：按 agentId 分目录后，两个智能体在同一 session 下互不干扰 */
    @Test
    @DisplayName("按 agentId 分目录后，同一 session 下多智能体状态互相隔离")
    void perAgentStateDirIsolatesAgents(@TempDir Path agentRoot) {
        Path coderDir = WorkspaceAgentBuilder.stateDir(agentRoot, "coder");
        Path reviewerDir = WorkspaceAgentBuilder.stateDir(agentRoot, "reviewer");
        assertNotEquals(coderDir, reviewerDir, "不同 agentId 应得到不同目录");

        JsonFileAgentStateStore coderStore = new JsonFileAgentStateStore(coderDir);
        JsonFileAgentStateStore reviewerStore = new JsonFileAgentStateStore(reviewerDir);

        coderStore.save(USER, SESSION, KEY, state("coder"));
        reviewerStore.save(USER, SESSION, KEY, state("reviewer"));

        assertEquals("coder", markerOf(coderStore), "coder 的状态不应被 reviewer 覆盖");
        assertEquals("reviewer", markerOf(reviewerStore), "reviewer 的状态不应被 coder 覆盖");
    }

    /**
     * 存量兼容红线：主智能体必须继续用原来的 {@code state} 目录。
     *
     * <p>若这里改成子目录，所有已存在的会话状态都会读不到（表现为「历史对话凭空消失」）。
     */
    @Test
    @DisplayName("存量兼容：主智能体仍使用原 state 目录，不加 agentId 子目录")
    void mainAgentKeepsLegacyStateDir(@TempDir Path agentRoot) {
        Path expected = agentRoot.resolve("state");

        assertEquals(expected, WorkspaceAgentBuilder.stateDir(agentRoot, SubagentLoader.MAIN_AGENT_ID),
                "主智能体状态目录不得变动，否则存量会话失联");
        assertEquals(expected, WorkspaceAgentBuilder.stateDir(agentRoot, null),
                "agentId 为 null 时应按主智能体处理");

        Path coderDir = WorkspaceAgentBuilder.stateDir(agentRoot, "coder");
        assertTrue(coderDir.startsWith(expected),
                "非主智能体目录应位于 state 之下，便于统一清理，实际=" + coderDir);
    }

    /** HarnessAgent name：主智能体保持 workspaceId，非主智能体用 agentId 以便区分 */
    @Test
    @DisplayName("harnessName：主智能体沿用 workspaceId，非主智能体用 agentId")
    void harnessNameDistinguishesAgents() {
        assertEquals("ws-1", WorkspaceAgentBuilder.harnessName("ws-1", SubagentLoader.MAIN_AGENT_ID));
        assertEquals("ws-1", WorkspaceAgentBuilder.harnessName("ws-1", null));
        assertEquals("coder", WorkspaceAgentBuilder.harnessName("ws-1", "coder"));
        assertNotEquals(
                WorkspaceAgentBuilder.harnessName("ws-1", "coder"),
                WorkspaceAgentBuilder.harnessName("ws-1", "reviewer"),
                "不同智能体的 name 必须可区分");
    }
}
