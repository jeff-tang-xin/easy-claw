package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.knowledge.KnowledgeService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceFileLayout;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 工作区种子播种器：把平台内置的 AGENTS.md 模板与种子知识条目播种到工作区。
 * <p>
 * 播种内容（全部<b>幂等，已存在的一律不覆盖</b>）：
 * <ul>
 *   <li>{@code <workspace>/.easyClaw/agent/AGENTS.md} —— 主控 Agent 工作规范模板。
 *       模板唯一来源是 classpath {@code /seed/AGENTS.md}（jar 内置），
 *       由 {@link WorkspaceFileLayout#repair} 在缺失时生成，用户改写过的绝不覆盖；</li>
 *   <li>知识条目 {@code agent-memory-governance} —— 持久化三层治理元规则。
 *       正文来自 classpath {@code /seed/agent-memory-governance.md}（body only，
 *       frontmatter 由 KnowledgeService 生成），经 {@link KnowledgeService#write} 写入
 *       以同步维护 {@code KNOWLEDGE.md} 索引；{@code exists} 命中
 *       （含用户改写过的版本）即跳过。</li>
 * </ul>
 * 触发时机：
 * <ul>
 *   <li>应用启动 —— {@link #seedAllWorkspaces()} 遍历全部存量工作区补播
 *       （{@code DataInitializer} 的 CommandLineRunner 驱动，仿 SystemDataSeeder 先例）；</li>
 *   <li>新建工作区 —— {@code WorkspaceManager#createWorkspace} 成功路径末尾内联调用
 *       {@link #seedWorkspace(Path)}。</li>
 * </ul>
 * 容错契约：单个工作区播种失败只记 warn，绝不阻断启动、不影响其他工作区、
 * 不影响新建工作区的主流程结果。
 * <p>
 * 为什么不并入 {@link SystemDataSeeder}：SystemDataSeeder 管系统级数据
 * （MCP/场景/表结构回填），粒度是「整个系统一次」；本类粒度是「单个工作区」，
 * 且需被 WorkspaceManager 在创建路径上调用。
 */
@Component
public class WorkspaceSeedService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSeedService.class);

    /** 种子知识 topic（= 文件名，不含扩展名）；AGENTS.md 模板中持久化分层一节引用的正是它 */
    static final String SEED_KNOWLEDGE_TOPIC = "agent-memory-governance";

    /** 种子知识 summary —— 与该条目历史前言一致；写入时由 KnowledgeService 生成 frontmatter */
    private static final String SEED_KNOWLEDGE_SUMMARY =
            "MEMORY 管行为、knowledge 管事实、blackboard 管任务进度；漂移即修、收尾晋升、销账闭环、二次踩坑四条触发线；主 Agent 归口修知识，子 Agent 只登记漂移。";

    /** 种子知识正文的 classpath 位置（body only） */
    private static final String SEED_KNOWLEDGE_RESOURCE = "/seed/agent-memory-governance.md";

    private final WorkspaceRepository workspaceRepository;
    private final KnowledgeService knowledgeService;
    private final WorkspaceFileLayout fileLayout;

    public WorkspaceSeedService(WorkspaceRepository workspaceRepository,
                                KnowledgeService knowledgeService,
                                WorkspaceFileLayout fileLayout) {
        this.workspaceRepository = workspaceRepository;
        this.knowledgeService = knowledgeService;
        this.fileLayout = fileLayout;
    }

    /**
     * 启动补播入口：遍历全部已登记工作区逐个播种。
     * 目录已不存在或播种抛错的工作区只记 warn 跳过，循环继续。
     */
    public void seedAllWorkspaces() {
        List<WorkspaceEntity> workspaces = workspaceRepository.findAll();
        int seeded = 0;
        for (WorkspaceEntity workspace : workspaces) {
            try {
                Path path = Paths.get(workspace.getPath());
                if (!Files.isDirectory(path)) {
                    log.warn("跳过种子播种（工作区目录不存在）: id={}, path={}",
                            workspace.getId(), workspace.getPath());
                    continue;
                }
                seedWorkspace(path);
                seeded++;
            } catch (Exception e) {
                // 单工作区失败不阻断启动与其余工作区
                log.warn("工作区种子播种失败（跳过，不影响其他工作区）: id={}, path={}, 原因={}",
                        workspace.getId(), workspace.getPath(), e.toString());
            }
        }
        log.info("工作区种子播种完成: 共 {} 个登记工作区，实际处理 {} 个", workspaces.size(), seeded);
    }

    /**
     * 播种单个工作区：AGENTS.md/MEMORY.md 模板 + 种子知识。
     * 全程幂等，已存在的文件/条目一律跳过不覆盖；任一步失败仅 warn。
     * 新建工作区（{@code WorkspaceManager#createWorkspace}）与启动补播共用本入口。
     */
    public void seedWorkspace(Path workspacePath) {
        try {
            // repair 的契约即「缺失才生成、绝不覆盖」；AGENTS.md 模板由 classpath /seed/AGENTS.md 提供
            fileLayout.repair(workspacePath.resolve(".easyClaw").resolve("agent"));
        } catch (Exception e) {
            log.warn("补齐工作区模板失败（可忽略，不影响主流程）: {} - {}", workspacePath, e.toString());
        }
        try {
            seedKnowledge(workspacePath);
        } catch (Exception e) {
            log.warn("播种工作区知识失败（可忽略，不影响主流程）: {} - {}", workspacePath, e.toString());
        }
    }

    /** 种子知识：exists 命中（含用户改写过的版本）即跳过，不覆盖 */
    private void seedKnowledge(Path workspacePath) throws IOException {
        WorkspaceContext ctx = WorkspaceContext.builder().path(workspacePath).build();
        if (knowledgeService.exists(SEED_KNOWLEDGE_TOPIC, ctx)) {
            return;
        }
        String content;
        try (InputStream in = WorkspaceSeedService.class.getResourceAsStream(SEED_KNOWLEDGE_RESOURCE)) {
            if (in == null) {
                log.warn("classpath 缺少 {}，跳过种子知识播种: {}", SEED_KNOWLEDGE_RESOURCE, workspacePath);
                return;
            }
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String result = knowledgeService.write(SEED_KNOWLEDGE_TOPIC, SEED_KNOWLEDGE_SUMMARY, content, ctx);
        if (result != null && result.startsWith("✅")) {
            log.info("已播种工作区知识 [{}]: {}", SEED_KNOWLEDGE_TOPIC, workspacePath);
        } else {
            log.warn("播种工作区知识 [{}] 失败: {} - {}", SEED_KNOWLEDGE_TOPIC, workspacePath, result);
        }
    }
}
