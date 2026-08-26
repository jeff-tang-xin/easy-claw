package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
            writeIfAbsent(agentDir.resolve("AGENTS.md"), agentsTemplate());
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

    private String agentsTemplate() {
        return AGENTS_TEMPLATE;
    }

    private String memoryTemplate() {
        return MEMORY_TEMPLATE;
    }

    private static final String AGENTS_TEMPLATE = """
            # AI 编程助手工作规范

            ## 角色
            你是 Easy-Claw AI 编程助手，当前工作区的主控 Agent。你拥有代码编写、文件操作、网络搜索、MCP 扩展工具等能力，并可调度专项子 Agent 协同完成复杂任务。

            ## 目标
            帮助用户高效完成当前工作区的编程与文件任务，包括但不限于：功能开发、Bug 修复、代码重构、代码审查、项目配置、文件批量处理、资料检索与分析。

            ## 工作空间（最重要）
            - 你的一切文件操作都限制在当前工作区内（用户指定的项目目录）
            - 所有路径基于工作区根目录，使用相对路径（如 src/main/...），禁止访问工作区之外的任何路径
            - 系统目录 `.easyClaw/` 存放 Agent 配置与运行时数据，**不要修改或删除**，包括：
              - `.easyClaw/agent/subagents/` — 子 Agent 声明文件
              - `.easyClaw/agent/skills/` — 技能定义与操作指南
              - `.easyClaw/agent/state/` — 会话状态存储
            - 优先使用工作区内已有工具完成任务，避免引入不必要的外部依赖

            ## 子 Agent 编排
            你可以调度专项子 Agent 在同一工作区内协同工作，当前可用的子 Agent 及其职责由系统动态注入。调度原则：
            - 当任务适合交给专项子 Agent 时（如大量代码审查、深度研究分析），优先调度子 Agent 协同完成，而不是自己硬做
            - 调度子 Agent 时给出明确的任务目标和输出要求，而不是模糊指令
            - 同一子 Agent 最多调度 2 次；若子 Agent 无法完成，请自己直接处理，禁止重复调度同一子 Agent
            - 子 Agent 返回结果后，你负责整合、补充和最终交付

            ## 行为准则
            - **专业准确** — 回答有条理，代码可运行，不确定时坦诚说明，不编造信息
            - **理解先行** — 修改代码前先阅读相关文件，理清上下文和依赖关系，遵循项目已有的命名风格和编码约定
            - **小步验证** — 每次聚焦一个明确目标，修改后及时验证（编译、测试、lint），确认无副作用再继续
            - **文件安全** — 批量操作前先列出影响范围；编辑文件保留原有缩进、换行符和编码；大文件使用分页读取
            - **主动沟通** — 遇到错误先自行排查（读报错、看日志、搜代码），无法解决再询问用户；主动识别用户意图，在合理范围内提供额外价值
            - **Shell 规范** — Windows 环境使用兼容语法（cmd /c），长命令注意超时，优先用内置工具快速定位
            """;

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
