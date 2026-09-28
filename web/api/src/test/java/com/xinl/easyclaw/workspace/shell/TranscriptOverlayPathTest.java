package com.xinl.easyclaw.workspace.shell;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归 SessionTree「rootPrefix 双拼」WARN。
 *
 * <p>线上现象（project 与 workspace 指向同一目录时，重启恢复长会话）：
 * <pre>
 * Failed to read transcript segment .easyClaw/agent/transcripts//.easyClaw/agent/transcripts/...
 * </pre>
 *
 * <p>根因：overlay 的 lower 层原本硬编码为 SANDBOXED（virtualMode=true），其 glob 经
 * {@code toVirtualPath} 返回带前导 {@code /} 的虚拟键；upper 为 ROOTED，返回不带 {@code /}
 * 的 cwd 相对键。两层 cwd 相同（project==workspace），{@code OverlayFilesystem.glob} 按
 * {@code fi.path()} 合并时同一物理文件成为两个不同条目；带 {@code /} 的键随后被
 * {@link ObjectStoreTranscriptStore#listSegments} 二次拼 rootPrefix 形成双拼 key，readSegment
 * 读不到而刷 WARN。修复把 lower 也改成 ROOTED，两层路径键方言一致、重复条目去重。
 *
 * <p>本测试走真实装配链 + 真实 {@link ObjectStoreTranscriptStore}：旧代码下 lower 虚拟键会被
 * 拼成 {@code rootPrefix//rootPrefix/...}，段数翻倍且 readSegment 抛
 * {@code IllegalStateException}("segment not readable")；修复后恰好两段、键无前导斜杠/无双拼、
 * 均可读。
 */
class TranscriptOverlayPathTest {

    @TempDir
    Path workspace;

    private static final String ROOT_PREFIX = ".easyClaw/agent/transcripts";

    /** 复现线上装配：project 与 workspace 同目录、ROOTED、projectWritable=true。 */
    private AbstractFilesystem buildOverlay() {
        LocalFilesystemSpec spec = new SafeShellFilesystemSpec();
        spec.mode(LocalFsMode.ROOTED);
        spec.project(workspace);
        spec.projectWritable(true);
        spec.isolationScope(IsolationScope.GLOBAL);
        return spec.toFilesystem(workspace, null);
    }

    @Test
    void 双层同目录时段不双拼且每段可读() {
        AbstractFilesystem fs = buildOverlay();
        TranscriptRef ref = new TranscriptRef("default", "agent-x", "session-y");
        TranscriptStore store =
                new ObjectStoreTranscriptStore(fs, RuntimeContext.empty(), ROOT_PREFIX);

        // 写入两个不可变段（走 upper，ROOTED 相对键）
        store.appendSegment(ref, 0, 10, "w1", "{\"seq\":0}\n".getBytes(StandardCharsets.UTF_8));
        store.appendSegment(ref, 11, 20, "w1", "{\"seq\":11}\n".getBytes(StandardCharsets.UTF_8));

        var segments = store.listSegments(ref);

        // 旧代码：upper/lower 路径键方言不同，同一物理文件被算两次 -> 4 段
        assertEquals(2, segments.size(), "同一物理文件不应被 upper/lower 各算一次: " + segments);

        for (var seg : segments) {
            String key = seg.key();
            assertFalse(key.startsWith("/"), "不应保留 SANDBOXED 虚拟键（带前导 /）: " + key);
            assertFalse(
                    key.contains(ROOT_PREFIX + "/" + ROOT_PREFIX),
                    "不应出现双拼前缀: " + key);
            assertTrue(key.startsWith(ROOT_PREFIX), "键应位于 rootPrefix 下: " + key);

            // 旧代码：双拼 key 在此抛 IllegalStateException("segment not readable: ...")（即线上 WARN）
            try (InputStream in = store.readSegment(key)) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("\"seq\""), "段内容应可读: " + key);
            } catch (Exception e) {
                throw new AssertionError("段不可读（双拼 WARN 复现）: " + key, e);
            }
        }
    }
}
