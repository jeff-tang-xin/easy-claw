package com.xinl.easyclaw.filesystem;

import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRLF 归一化版的 {@link LocalFilesystemSpec}。
 * <p>
 * 框架的 LocalFilesystemSpec.toFilesystem() 内部 new LocalFilesystem / LocalFilesystemWithShell
 * 没有注入点，因此通过子类化覆盖 toFilesystem()，使用 CRLF 修复版的文件系统实现。
 * 外部调用 .mode() / .project() / .projectWritable(true) / .env() / .inheritEnv() 等
 * setter 方法时，同时记录到本类字段，避免反射读取父类 private 字段。
 */
public class CrlfNormalizingLocalFilesystemSpec extends LocalFilesystemSpec {

    private static final Logger log = LoggerFactory.getLogger(CrlfNormalizingLocalFilesystemSpec.class);

    private static final int DEFAULT_EXECUTE_TIMEOUT = 120;
    private static final int DEFAULT_MAX_OUTPUT = 100_000;

    private final Map<String, String> env = new LinkedHashMap<>();
    private boolean inheritEnv = true;
    private int executeTimeoutSeconds = DEFAULT_EXECUTE_TIMEOUT;
    private int maxOutputBytes = DEFAULT_MAX_OUTPUT;

    @Override
    public LocalFilesystemSpec env(String name, String value) {
        env.put(name, value);
        return super.env(name, value);
    }

    @Override
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.inheritEnv = inherit;
        return super.inheritEnv(inherit);
    }

    @Override
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        this.executeTimeoutSeconds = seconds;
        return super.executeTimeoutSeconds(seconds);
    }

    @Override
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        this.maxOutputBytes = bytes;
        return super.maxOutputBytes(bytes);
    }

    @Override
    public io.agentscope.harness.agent.filesystem.AbstractFilesystem toFilesystem(
            Path workspace, NamespaceFactory localNamespaceFactory) {
        Path effectiveProject = getProject() != null
                ? getProject()
                : Paths.get(System.getProperty("user.dir"));

        List<Path> policyRoots = new ArrayList<>();
        policyRoots.add(effectiveProject);
        policyRoots.add(workspace);
        policyRoots.addAll(getAdditionalRoots());
        PathPolicy pathPolicy = PathPolicy.of(policyRoots);

        LocalFsMode mode = getMode() != null ? getMode() : LocalFsMode.ROOTED;

        Map<String, String> envMap = env.isEmpty() ? null : Map.copyOf(env);

        CrlfNormalizingLocalFilesystemWithShell upper = new CrlfNormalizingLocalFilesystemWithShell(
                workspace, mode, pathPolicy, executeTimeoutSeconds, maxOutputBytes,
                envMap, inheritEnv, localNamespaceFactory, effectiveProject);

        CrlfNormalizingLocalFilesystem lower = new CrlfNormalizingLocalFilesystem(
                effectiveProject, LocalFsMode.SANDBOXED, PathPolicy.empty(), 10, null);

        if (isProjectWritable()) {
            CrlfNormalizingLocalFilesystem projectFs = new CrlfNormalizingLocalFilesystem(
                    effectiveProject, mode, pathPolicy, 10, localNamespaceFactory);
            return new ProjectAwareOverlay(
                    (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
        }
        return OverlayFilesystem.of(upper, lower);
    }
}
