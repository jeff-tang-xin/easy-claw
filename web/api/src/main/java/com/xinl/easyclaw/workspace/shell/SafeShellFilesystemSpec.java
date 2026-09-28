package com.xinl.easyclaw.workspace.shell;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配 {@link SafeShellFilesystem} 的 spec：与 vendored {@link LocalFilesystemSpec} 行为一致，
 * 仅把 shell 宿主从 LocalFilesystemWithShell 换成修复三重进程缺陷的 SafeShellFilesystem。
 *
 * <p>vendored spec 的 executeTimeoutSeconds/maxOutputBytes/env/inheritEnv 为 private 无 getter，
 * 本类覆写对应 setter 自存一份（同时调 super 保持父类状态一致）；toFilesystem 对象图与
 * vendored 逐行等价（projectWritable 分支走 ProjectAwareOverlay），保证除 execute() 外零漂移。
 *
 * <p>生效路径：builder.filesystem(spec) 与 fsSpec.toFilesystem() 两处消费均经多态走到本类覆写，
 * 装配侧只需把 new LocalFilesystemSpec() 换成 new SafeShellFilesystemSpec()。
 */
public class SafeShellFilesystemSpec extends LocalFilesystemSpec {

    // 默认值与 vendored LocalFilesystemSpec/LocalFilesystemWithShell 对齐；装配侧 setter 必覆盖
    private int safeTimeout = 120;
    private int safeMaxOutputBytes = 100_000;
    private final Map<String, String> safeEnv = new LinkedHashMap<>();
    private boolean safeInheritEnv = false;

    @Override
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        this.safeTimeout = seconds;
        return super.executeTimeoutSeconds(seconds);
    }

    @Override
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        this.safeMaxOutputBytes = bytes;
        return super.maxOutputBytes(bytes);
    }

    @Override
    public LocalFilesystemSpec env(String name, String value) {
        this.safeEnv.put(name, value);
        return super.env(name, value);
    }

    @Override
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.safeInheritEnv = inherit;
        return super.inheritEnv(inherit);
    }

    @Override
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
        Path effectiveProject =
                getProject() != null ? getProject() : Paths.get(System.getProperty("user.dir"));
        List<Path> policyRoots = new ArrayList<>();
        policyRoots.add(effectiveProject);
        policyRoots.add(workspace);
        policyRoots.addAll(getAdditionalRoots());
        PathPolicy pathPolicy = PathPolicy.of(policyRoots);
        SafeShellFilesystem upper =
                new SafeShellFilesystem(
                        workspace,
                        getMode(),
                        pathPolicy,
                        safeTimeout,
                        safeMaxOutputBytes,
                        safeEnv.isEmpty() ? null : Map.copyOf(safeEnv),
                        safeInheritEnv,
                        localNamespaceFactory,
                        effectiveProject);
        // lower 必须与 upper 同为 ROOTED（而非 vendored 原本硬编码的 SANDBOXED / virtualMode=true）。
        // 当 project 与 workspace 指向同一目录（本项目的装配即如此）时，两层 cwd 相同：
        // ROOTED 的 glob 经 toCwdRelativePath 返回不带前导 '/' 的相对键，而 SANDBOXED 经
        // toVirtualPath 返回带前导 '/' 的虚拟键，OverlayFilesystem.glob 按 fi.path() 合并时
        // 同一物理文件会被当成两个不同条目；带 '/' 的键随后被 ObjectStoreTranscriptStore
        // 二次拼接 rootPrefix，产生 "rootPrefix//rootPrefix/..." 双拼 key，导致重启恢复长会话时
        // 读不到 transcript 段并刷 WARN。统一为 ROOTED 后两层键方言一致、重复条目自然去重；
        // lower 仍是只读 fallback（overlay 写操作只落 upper），相对键在两种模式下解析完全等价。
        LocalFilesystem lower =
                new LocalFilesystem(effectiveProject, LocalFsMode.ROOTED, pathPolicy, 10, null);
        if (isProjectWritable()) {
            LocalFilesystem projectFs =
                    new LocalFilesystem(
                            effectiveProject, getMode(), pathPolicy, 10, localNamespaceFactory);
            return new ProjectAwareOverlay(
                    (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
        }
        return OverlayFilesystem.of(upper, lower);
    }
}
