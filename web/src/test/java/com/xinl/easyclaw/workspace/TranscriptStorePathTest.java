package com.xinl.easyclaw.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回归测试：transcript 必须落在 {@code .easyClaw/agent/transcripts} 下，
 * 不能散落到 workspace 根生成 {@code default/} 目录。
 *
 * <p>背景：HarnessAgent 默认兜底使用 {@code new ObjectStoreTranscriptStore(fs)} 单参构造，
 * 其 rootPrefix 为空串，导致 key 直接拼成 {@code <tenant>/<agentId>/<sessionId>/events/...}，
 * 因 filesystem 基准是项目根，会在项目根生成 default/ 目录。
 * WorkspaceAgentBuilder 已显式注入带 rootPrefix 的实例来修正。
 */
class TranscriptStorePathTest {

    private static final String PREFIX = ".easyClaw/agent/transcripts";

    @Test
    @DisplayName("注入 rootPrefix 后 transcript 落在 .easyClaw/agent/transcripts 下")
    void writesUnderEasyClawDir(@TempDir Path workspace) {
        AbstractFilesystem fs = new LocalFilesystemSpec().toFilesystem(workspace, null);
        ObjectStoreTranscriptStore store =
                new ObjectStoreTranscriptStore(fs, RuntimeContext.empty(), PREFIX);

        TranscriptRef ref = new TranscriptRef("default", "agent-x", "session-1");
        String key = store.appendSegment(ref, 1, 2, "writer", "{\"a\":1}\n".getBytes());

        assertTrue(key.startsWith(PREFIX + "/"), "key 应带 rootPrefix，实际=" + key);

        Path expected =
                workspace.resolve(PREFIX).resolve("default/agent-x/session-1/events");
        assertTrue(Files.isDirectory(expected), "应在 .easyClaw 下生成 events 目录: " + expected);

        // 核心断言：workspace 根不得出现裸露的 default/ 目录
        assertFalse(
                Files.exists(workspace.resolve("default")),
                "workspace 根不应生成 default/ 目录");
    }

    @Test
    @DisplayName("反向验证：不传 rootPrefix 时会在根目录生成 default/（即原缺陷）")
    void reproducesDefectWithoutPrefix(@TempDir Path workspace) {
        AbstractFilesystem fs = new LocalFilesystemSpec().toFilesystem(workspace, null);
        ObjectStoreTranscriptStore store = new ObjectStoreTranscriptStore(fs);

        TranscriptRef ref = new TranscriptRef("default", "agent-x", "session-1");
        store.appendSegment(ref, 1, 2, "writer", "{\"a\":1}\n".getBytes());

        // 证明缺陷确实存在，从而说明显式注入是必要的
        assertTrue(
                Files.exists(workspace.resolve("default")),
                "未传 rootPrefix 时应复现出根目录 default/");
    }
}
