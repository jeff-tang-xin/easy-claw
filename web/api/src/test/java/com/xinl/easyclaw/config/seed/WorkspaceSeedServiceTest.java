package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.knowledge.LocalKnowledgeService;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceFileLayout;
import com.xinl.easyclaw.workspace.entity.WorkspaceEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkspaceSeedService} 的行为约束。
 * <p>
 * 核心红线：<b>幂等且绝不覆盖</b>——AGENTS.md 与种子知识一旦被用户改写过，
 * 启动补播与新建播种都不得回滚用户内容；否则「播种」会变成「周期性破坏」。
 */
@DisplayName("工作区种子播种")
class WorkspaceSeedServiceTest {

    private WorkspaceSeedService newService(WorkspaceRepository repository) {
        return new WorkspaceSeedService(repository, new LocalKnowledgeService(), new WorkspaceFileLayout());
    }

    private WorkspaceContext ws(Path root) {
        return WorkspaceContext.builder().workspaceId("ws-seed-test").path(root).build();
    }

    private Path agentsMd(Path root) {
        return root.resolve(".easyClaw").resolve("agent").resolve("AGENTS.md");
    }

    @Test
    @DisplayName("首次播种：生成 AGENTS.md 模板并写入种子知识")
    void seedsTemplateAndKnowledge(@TempDir Path root) {
        WorkspaceSeedService svc = newService(mock(WorkspaceRepository.class));

        svc.seedWorkspace(root);

        assertTrue(Files.exists(agentsMd(root)), "缺失的 AGENTS.md 必须被模板补齐");
        LocalKnowledgeService kb = new LocalKnowledgeService();
        assertTrue(kb.exists(WorkspaceSeedService.SEED_KNOWLEDGE_TOPIC, ws(root)), "种子知识必须写入");
        assertTrue(kb.read(WorkspaceSeedService.SEED_KNOWLEDGE_TOPIC, ws(root)).contains("blackboard"),
                "种子知识正文应来自 classpath /seed/agent-memory-governance.md");
    }

    @Test
    @DisplayName("用户改写过的 AGENTS.md 与知识条目一律不覆盖")
    void neverOverwritesUserContent(@TempDir Path root) throws IOException {
        WorkspaceSeedService svc = newService(mock(WorkspaceRepository.class));
        svc.seedWorkspace(root); // 首次播种出模板与知识

        // 用户改写两处
        Files.writeString(agentsMd(root), "# 用户自己的工作规范\n", StandardCharsets.UTF_8);
        LocalKnowledgeService kb = new LocalKnowledgeService();
        kb.write(WorkspaceSeedService.SEED_KNOWLEDGE_TOPIC, "用户改写", "# 用户版治理\n", ws(root));

        svc.seedWorkspace(root); // 再播（启动补播同款路径）

        assertEquals("# 用户自己的工作规范\n", Files.readString(agentsMd(root), StandardCharsets.UTF_8),
                "用户改写过的 AGENTS.md 绝不允许被模板回滚");
        assertTrue(kb.read(WorkspaceSeedService.SEED_KNOWLEDGE_TOPIC, ws(root)).contains("用户版治理"),
                "用户改写过的知识条目绝不允许被种子回滚");
    }

    @Test
    @DisplayName("幂等：重复播种索引不重复膨胀")
    void idempotentOnRepeat(@TempDir Path root) throws IOException {
        WorkspaceSeedService svc = newService(mock(WorkspaceRepository.class));
        svc.seedWorkspace(root);
        svc.seedWorkspace(root);
        svc.seedWorkspace(root);

        Path index = root.resolve(".easyClaw").resolve("agent").resolve("knowledge").resolve("KNOWLEDGE.md");
        String indexText = Files.readString(index, StandardCharsets.UTF_8);
        String link = "[" + WorkspaceSeedService.SEED_KNOWLEDGE_TOPIC + "](";
        int first = indexText.indexOf(link);
        assertTrue(first >= 0, "索引应含种子条目链接");
        assertEquals(-1, indexText.indexOf(link, first + link.length()), "重复播种不得在索引中累积重复行");
    }

    @Test
    @DisplayName("启动补播：目录不存在的工作区跳过，正常工作区完成播种，全程不抛异常")
    void seedAllSkipsMissingAndContinues(@TempDir Path root) {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        WorkspaceEntity missing = WorkspaceEntity.builder().path(root.resolve("not-exist-dir").toString()).build();
        WorkspaceEntity normal = WorkspaceEntity.builder().path(root.toString()).build();
        when(repository.findAll()).thenReturn(List.of(missing, normal));

        WorkspaceSeedService svc = newService(repository);
        assertDoesNotThrow(svc::seedAllWorkspaces, "单工作区异常绝不允许阻断启动");

        assertTrue(Files.exists(agentsMd(root)), "正常工作区应完成播种");
    }
}
