package com.xinl.easyclaw.agent.team;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceAgentBuilder;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * lead team 变体装配验证：缓存语义（同会话复用同一实例）、releaseSession 清理、
 * 成员工厂委托 buildMember。
 */
class TeamRuntimeServiceLeadTest {

    @TempDir
    Path tmp;

    private SubagentDeclaration decl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description(name + " 成员")
                .steps(30)
                .build();
    }

    @Test
    void leadAgentForCachesPerSessionAndReleaseClears() {
        WorkspaceAgentBuilder builder = mock(WorkspaceAgentBuilder.class);
        // thenAnswer：每次调用返回新 mock 实例——否则 releaseSession 后「重建」断言无法区分缓存命中与 mock 复用
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        when(builder.build(anyString(), eq(SubagentLoader.MAIN_AGENT_ID), anyString(),
                any(Path.class), any(Path.class), isNull(), anyString(),
                any(EasyClawTeamClient.class), any(io.agentscope.harness.agent.team.TeamContext.class),
                anyString())).thenAnswer(inv -> mock(HarnessAgent.class, "lead-" + seq.incrementAndGet()));
        WorkspaceManager wsManager = mock(WorkspaceManager.class);
        when(wsManager.getWorkspace("ws1")).thenReturn(WorkspaceContext.builder()
                .workspaceId("ws1").userId("u1").name("demo")
                .path(tmp).createdAt(Instant.now()).lastAccessed(Instant.now())
                .build());

        TeamRuntimeService svc = new TeamRuntimeService(
                mock(SubagentLoader.class), mock(ScenarioResolver.class),
                null, builder, wsManager);

        HarnessAgent a1 = svc.leadAgentFor("ws1", "s1", "alpha", "目标", List.of());
        HarnessAgent a2 = svc.leadAgentFor("ws1", "s1", "alpha", "目标", List.of());
        assertSame(a1, a2, "同会话二次取用必须命中缓存，不得重建 lead 实例");
        verify(builder, times(1)).build(anyString(), anyString(), anyString(),
                any(Path.class), any(Path.class), any(), anyString(),
                any(), any(), anyString());
        assertTrue(svc.hasTeam("ws1", "s1"), "leadAgentFor 应联动 startTeam");

        // 不同会话不同实例
        HarnessAgent b1 = svc.leadAgentFor("ws1", "s2", "beta", "目标", List.of());
        assertNotSame(a1, b1);

        // releaseSession：缓存清空 + team 收尾
        svc.releaseSession("ws1", "s1");
        assertFalse(svc.hasTeam("ws1", "s1"));
        HarnessAgent a3 = svc.leadAgentFor("ws1", "s1", "alpha", "目标", List.of());
        assertNotSame(a1, a3, "释放后重建新实例");
    }

    @Test
    void memberFactoryDelegatesToBuildMember() {
        WorkspaceAgentBuilder builder = mock(WorkspaceAgentBuilder.class);
        HarnessAgent memberAgent = mock(HarnessAgent.class);
        when(builder.buildMember(anyString(), any(SubagentDeclaration.class),
                any(Path.class), any(Path.class), any(EasyClawTeamClient.class),
                any(io.agentscope.harness.agent.team.TeamContext.class), anyString()))
                .thenReturn(memberAgent);
        WorkspaceManager wsManager = mock(WorkspaceManager.class);
        when(wsManager.getWorkspace("ws1")).thenReturn(WorkspaceContext.builder()
                .workspaceId("ws1").userId("u1").name("demo")
                .path(tmp).createdAt(Instant.now()).lastAccessed(Instant.now())
                .build());

        TeamMemberAgentFactory factory = new TeamMemberAgentFactory(builder, wsManager);
        io.agentscope.harness.agent.team.TeamContext ctx = new io.agentscope.harness.agent.team.TeamContext(
                "alpha", "ws1", "目标", "coder", false, List.of(),
                TeamRuntimeService.MEMBER_ACTIONS);
        HarnessAgent agent = factory.create(decl("coder"), "s1/members/coder", ctx,
                mock(EasyClawTeamClient.class));

        assertSame(memberAgent, agent);
        verify(builder).buildMember(eq("ws1"), any(SubagentDeclaration.class),
                eq(tmp), eq(tmp.resolve(".easyClaw")), any(EasyClawTeamClient.class),
                eq(ctx), eq("s1/members/coder"));
    }
}
