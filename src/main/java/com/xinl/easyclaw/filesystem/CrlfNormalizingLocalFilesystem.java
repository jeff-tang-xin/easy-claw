package com.xinl.easyclaw.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 修复 AgentScope {@link LocalFilesystem#read} 的 CRLF bug。
 * <p>
 * 框架的 read() 方法直接用 {@code content.split("\n")} 切行，没有先把
 * Windows 的 {@code \r\n} 归一化为 {@code \n}，导致每行末尾带 {@code \r}：
 * <ul>
 *   <li>返回给 LLM 的每行内容末尾多了一个不可见的 \r 字符</li>
 *   <li>edit_file 的 old_string 匹配失败（因为 edit 做了归一化但 read 没做）</li>
 *   <li>grep 返回的行号正确，但行内容带 \r 导致 LLM 粘贴代码出错</li>
 * </ul>
 * edit() 方法在框架中已经正确做了 CRLF 归一化，唯独 read() 遗漏了。
 * 本类通过重写 read() 来补齐这个归一化步骤。
 */
public class CrlfNormalizingLocalFilesystem extends LocalFilesystem {

    private static final Logger log = LoggerFactory.getLogger(CrlfNormalizingLocalFilesystem.class);

    public CrlfNormalizingLocalFilesystem(Path rootDir) {
        super(rootDir);
    }

    public CrlfNormalizingLocalFilesystem(Path rootDir, LocalFsMode mode, PathPolicy pathPolicy,
                                          int maxFileSizeMb, NamespaceFactory namespaceFactory) {
        super(rootDir, mode, pathPolicy, maxFileSizeMb, namespaceFactory);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Path resolved = resolvePath(runtimeContext, filePath);
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            String normalized = content.replace("\r\n", "\n").replace("\r", "\n");

            if (normalized.isEmpty() || normalized.isBlank()) {
                return ReadResult.success(
                        new FileData("System reminder: File exists but has empty contents", "utf-8"));
            }

            String[] lines = normalized.split("\n", -1);
            int startIdx = Math.max(0, offset);
            int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;
            if (startIdx >= lines.length) {
                return ReadResult.fail(
                        "Line offset " + offset + " exceeds file length (" + lines.length + " lines)");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                if (i > startIdx) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            return ReadResult.success(new FileData(sb.toString(), "utf-8"));
        } catch (Exception e) {
            return ReadResult.fail("Error reading file '" + filePath + "': " + e.getMessage());
        }
    }
}
