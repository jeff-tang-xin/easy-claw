package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区磁盘布局维护者。
 * <p>
 * 从 {@code WorkspaceManager} 抽出的第二层职责：**只管目录与文件**，不碰 JPA、
 * 不碰 {@code HarnessAgent}。原先这三种关注点挤在同一个 48KB 的类里，导致
 * 「读工作区」这样的热路径方法内部藏着磁盘写（{@code ensureWorkspaceFiles}），
 * 每轮对话都做一遍文件系统检查。
 * <p>
 * <b>幂等契约</b>：本类所有方法可重复调用而不破坏用户数据 ——
 * 模板文件只在缺失时生成（绝不覆盖用户修改），迁移只在目标不存在时执行。
 * 因此 {@link #repair} 可以安全地暴露为显式「修复」操作。
 * <p>
 * <b>调用时机</b>：{@link #initialize} 仅在工作区创建 / 首次加载时调用；
 * {@link #repair} 仅在用户显式请求修复时调用。**不得**放进每轮对话的读路径。
 * <p>
 * <b>失败策略</b>：模板补齐失败只记 warn（缺 AGENTS.md 不影响对话），
 * 结构初始化失败则抛异常（目录建不出来说明路径不可用，继续下去会在更晚的地方
 * 以更难诊断的形式失败）。
 */
@Component
public class WorkspaceFileLayout {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileLayout.class);

    /** .easyClaw/agent 下必须存在的子目录 */
    private static final List<String> AGENT_SUBDIRS = List.of("state", "skills", "subagents");

    /** AGENTS.md 模板的 classpath 位置（jar 内置唯一来源，内容 = 平台当前默认工作规范） */
    private static final String AGENTS_TEMPLATE_RESOURCE = "/seed/AGENTS.md";

    /** AGENTS.md 模板缓存（懒加载；null 表示尚未加载过，加载失败不缓存、下次重试） */
    private volatile String agentsTemplateCache;

    // ==================== 对外入口 ====================

    /**
     * 按 Easy-Claw 规范初始化工作区结构（仅首次创建或迁移时调用）：
     * 迁移旧目录 → 创建基础目录 → 迁移根级遗留文件 → 补齐模板 → 清理遗留目录。
     *
     * @throws IllegalStateException 目录创建失败（路径不可写等），调用方应中止创建流程
     */
    public void initialize(Path workspacePath, Path easyClawDir) {
        try {
            Path agentDir = easyClawDir.resolve("agent");

            migrateLegacyAgentscopeDir(workspacePath, easyClawDir, agentDir);
            createAgentDirs(agentDir);

            // 旧版本在 workspace 根生成的文件迁移到 .easyClaw/agent（目标已存在则不覆盖）
            migrateIfAbsent(workspacePath.resolve("AGENTS.md"), agentDir.resolve("AGENTS.md"));
            migrateIfAbsent(workspacePath.resolve("MEMORY.md"), agentDir.resolve("MEMORY.md"));
            migrateDirIfAbsent(workspacePath.resolve("skills"), agentDir.resolve("skills"));
            migrateDirIfAbsent(workspacePath.resolve("subagents"), agentDir.resolve("subagents"));

            repair(agentDir);
            cleanupLegacyDirs(workspacePath);
        } catch (IOException e) {
            log.error("初始化 Workspace 结构失败: {}", workspacePath, e);
            throw new IllegalStateException("初始化 Workspace 结构失败: " + workspacePath, e);
        }
    }

    /**
     * 补齐 .easyClaw/agent 下的模板文件（仅在不存在时创建，不覆盖用户修改）。
     * <p>
     * 供工作区创建与显式「修复」操作调用；用户误删 AGENTS.md / MEMORY.md 后可借此恢复。
     */
    public void repair(Path agentDir) {
        try {
            createAgentDirs(agentDir);
            String agentsTemplate = agentsTemplate();
            if (agentsTemplate != null) {
                // 模板缺失/读取失败时跳过本项（已记 warn），不阻断其余补齐
                writeIfAbsent(agentDir.resolve("AGENTS.md"), agentsTemplate);
            }
            writeIfAbsent(agentDir.resolve("MEMORY.md"), memoryTemplate());
            // 注意：此处不播种 reviewer.md 等内置子 Agent。
            // 内置角色（planner/coder/reviewer...）统一由 SystemDataSeeder 播种到全局目录，
            // 工作区级 subagents/ 只存放用户自定义或刻意覆盖的声明。
            // 历史实现在这里额外写了一份 reviewer.md（steps=8），因 loadMerged 中
            // 「workspace 覆盖 global」的规则，反而压制了全局的宽松配置，导致
            // 子 Agent 迭代提前耗尽、回复被 ExceedMaxItersEvent 截断。
        } catch (IOException e) {
            // 模板缺失不影响对话主流程，不升级为异常
            log.warn("补齐 Workspace 模板文件失败: {}", agentDir, e);
        }
    }

    /**
     * 会话状态目录：{@code <workspace>/.easyClaw/agent/state/{userId}/{sessionId}}。
     * <p>
     * 与框架 {@code JsonFileAgentStateStore} 的布局一致（它的 root 是
     * {@code .easyClaw/agent/state}，内部再按 {@code <userId>/<sessionId>/<key>.json} 展开），
     * 因此这里算出的目录就是框架落盘的同一位置。
     * <p>
     * 抽出此方法的原因：此前 {@code AgentService}、{@code ChatController} 各自
     * 手工拼接同一路径，字符串分散在多处，改动布局时容易漏改。
     */
    public Path sessionStateDir(Path workspacePath, String userId, String sessionId) {
        String uid = (userId == null || userId.isBlank()) ? AppConstants.DEFAULT_USER_ID : userId;
        return workspacePath.resolve(".easyClaw/agent/state").resolve(uid).resolve(sessionId);
    }

    /** 会话状态文件：会话状态目录下的 {@code agent_state.json}（框架 key = {@code agent_state}） */
    public Path sessionStateFile(Path workspacePath, String userId, String sessionId) {
        return sessionStateDir(workspacePath, userId, sessionId).resolve(AGENT_STATE_FILE);
    }

    /** 框架 {@code AgentStateStore} 中 Agent 状态的 key，对应磁盘文件 {@code agent_state.json} */
    public static final String AGENT_STATE_KEY = "agent_state";

    /** Agent 状态文件名 */
    public static final String AGENT_STATE_FILE = AGENT_STATE_KEY + ".json";

    /**
     * 原子写入文本文件：先写同目录临时文件，再 {@code ATOMIC_MOVE} 覆盖目标。
     * <p>
     * 与框架 {@code JsonFileAgentStateStore.save()} 的落盘方式一致。直接
     * {@code Files.writeString} 在写入中途进程退出时会留下**被截断的**
     * {@code agent_state.json}，下次启动反序列化失败即等于该会话历史全丢；
     * 临时文件 + 原子改名可保证目标文件要么是旧内容、要么是完整新内容。
     * <p>
     * 注意：这**不提供**并发写保护。实际装配的 {@code JsonFileAgentStateStore}
     * 未 override {@code supportsVersioning()}，框架的 {@code saveIfVersion} 也退化为
     * last-writer-wins，故此处与走框架 API 的并发语义相同。
     */
    public void atomicWriteString(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 少数文件系统（部分网络盘）不支持原子改名，退化为普通替换
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 递归删除目录（供工作区删除流程复用） */
    public void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    throw new UncheckedDeleteException(ex);
                }
            });
        } catch (UncheckedDeleteException e) {
            throw e.getCause();
        }
    }

    /** 包装 forEach 内的 IOException，使其能在 walk 外被还原为受检异常 */
    private static final class UncheckedDeleteException extends RuntimeException {
        UncheckedDeleteException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    // ==================== 内部实现 ====================

    private void createAgentDirs(Path agentDir) throws IOException {
        Files.createDirectories(agentDir);
        for (String sub : AGENT_SUBDIRS) {
            Files.createDirectories(agentDir.resolve(sub));
        }
    }

    private void writeIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, content);
            log.info("已生成模板文件: {}", file);
        }
    }

    /** 迁移旧 .agentscope 目录 → .easyClaw/agent（保留对话历史） */
    private void migrateLegacyAgentscopeDir(Path workspacePath, Path easyClawDir, Path agentDir)
            throws IOException {
        Path legacy = workspacePath.resolve(".agentscope");
        if (!Files.exists(legacy) || Files.exists(easyClawDir)) {
            return;
        }
        Files.createDirectories(agentDir);
        moveTree(legacy, agentDir);
        log.info("已迁移旧目录 {} → {}", legacy, agentDir);
    }

    /** 迁移单个文件（目标已存在则不覆盖） */
    private void migrateIfAbsent(Path src, Path dst) throws IOException {
        if (Files.exists(src) && !Files.exists(dst)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst);
            log.info("已迁移 {} → {}", src, dst);
        }
    }

    /**
     * 把遗留目录的内容合并进目标目录。
     * <p>
     * 这里必须是「按文件合并」而不是「整目录搬移」：{@link #createAgentDirs} 已经
     * 预先建好了 {@code skills/}、{@code subagents/}，若沿用「目标存在就跳过」的判断，
     * 根级遗留目录将永远无法迁移（历史实现即如此，用户放在 workspace 根的
     * 自定义 skills 会被静默忽略）。
     */
    private void migrateDirIfAbsent(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            return;
        }
        Files.createDirectories(dst);
        moveTree(src, dst);
        log.info("已迁移目录 {} → {}", src, dst);
    }

    /**
     * 把 src 下的全部**文件**搬到 dst 对应位置，随后删除 src 整棵树。
     * <p>
     * 只搬文件、不搬目录：目录用 {@code Files.move} 在目标已存在且非空时会抛
     * {@code DirectoryNotEmptyException}，历史实现把它吞进 {@code ignored}，
     * 却导致 src 残留非空、后续 {@code deleteIfExists(src)} 抛异常，
     * 最终让整个工作区初始化失败。
     * <p>
     * 目标已存在的文件保留目标版本（现役数据优先于遗留数据）。
     * 单个文件迁移失败被忽略：迁移是尽力而为的兼容动作，不应阻断工作区创建。
     */
    private void moveTree(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        Files.move(p, target);
                    }
                } catch (IOException ignored) {
                    // 忽略单个文件迁移失败
                }
            });
        }
        deleteRecursively(src);
    }

    /**
     * 删除 harness 旧版遗留的 &lt;workspace&gt;/&lt;userId&gt; 目录（agents/sessions 会话文件），
     * 状态已由 .easyClaw/agent/state 接管。
     */
    private void cleanupLegacyDirs(Path workspacePath) {
        for (String userId : List.of(AppConstants.LEGACY_USER_ID, AppConstants.DEFAULT_USER_ID)) {
            for (Path legacy : List.of(
                    workspacePath.resolve(userId + "/agents"),
                    workspacePath.resolve(userId + "/sessions"),
                    workspacePath.resolve(userId))) {
                tryDelete(legacy);
            }
        }
        // Harness 默认的 ".agentscope" bus 目录也清掉（已 override 到 .easyClaw/bus）
        tryDelete(workspacePath.resolve(".agentscope"));
    }

    private void tryDelete(Path legacy) {
        if (!Files.exists(legacy)) {
            return;
        }
        try {
            deleteRecursively(legacy);
            log.info("已清理 harness 遗留目录: {}", legacy);
        } catch (IOException e) {
            log.warn("清理遗留目录失败 {}: {}", legacy, e.getMessage());
        }
    }

    // ==================== 模板内容 ====================

    /**
     * AGENTS.md 模板：classpath {@code /seed/AGENTS.md} 为唯一来源（jar 内置），懒加载并缓存。
     * 资源缺失或读取失败时返回 null —— {@link #repair} 跳过该项（已记 warn），不影响其余补齐。
     */
    private String agentsTemplate() {
        if (agentsTemplateCache == null) {
            try (InputStream in = WorkspaceFileLayout.class.getResourceAsStream(AGENTS_TEMPLATE_RESOURCE)) {
                if (in == null) {
                    log.warn("classpath 缺少 {}，跳过 AGENTS.md 模板补齐", AGENTS_TEMPLATE_RESOURCE);
                    return null;
                }
                agentsTemplateCache = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取 AGENTS.md 模板失败（跳过补齐）: {}", e.toString());
                return null;
            }
        }
        return agentsTemplateCache;
    }

    private String memoryTemplate() {
        return MEMORY_TEMPLATE;
    }

    private static final String MEMORY_TEMPLATE = """
            # 工作区记忆

            > 本文件由 Agent 自动维护，用于沉淀跨会话的重要信息。
            > 仅在发现有长期价值的内容时更新，不要记录临时或一次性信息。

            ## 项目概览
            - 项目类型与技术栈：
            - 构建工具与命令：
            - 目录结构说明：

            ## 代码约定
            - 命名风格：
            - 编码规范：
            - 特殊模式或惯用写法：

            ## 用户偏好
            - 语言与输出风格：
            - 工具使用习惯：
            - 禁忌或特殊要求：

            ## 关键决策记录
            | 日期 | 决策内容 | 原因/背景 |
            |------|---------|----------|
            |      |         |          |

            ## 已知问题与 TODO
            - [ ] 
            """;
}
