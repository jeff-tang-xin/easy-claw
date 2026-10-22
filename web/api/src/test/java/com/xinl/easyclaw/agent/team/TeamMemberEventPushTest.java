package com.xinl.easyclaw.agent.team;

import com.xinl.easyclaw.agent.SubagentLoader;
import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceAgentBuilder;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成员事件推送验证：AgentEvent → StreamEvent.subagent* 翻译、工具结果累积下发、
 * sink 路由到 lead 会话。
 */
class TeamMemberEventPushTest {

    @TempDir
    Path tmp;

    @Test
    void memberTurnTranslatesEventsAndRoutesToSink() throws Exception {
        WorkspaceAgentBuilder builder = mock(WorkspaceAgentBuilder.class);
        HarnessAgent memberAgent = mock(HarnessAgent.class);
        // 成员回合事件流：文本 delta → 工具开始 → 工具结果 delta×2 → 工具结束 → 结束
        when(memberAgent.streamEvents(any(String.class),
                any(io.agentscope.core.agent.RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new TextBlockDeltaEvent("r1", "b1", "你好"),
                new ToolCallStartEvent("r1", "c1", "listTasks"),
                new ToolResultTextDeltaEvent("r1", "c1", "listTasks", "任务"),
                new ToolResultTextDeltaEvent("r1", "c1", "listTasks", "板空"),
                new ToolResultEndEvent("r1", "c1", "listTasks",
                        io.agentscope.core.message.ToolResultState.SUCCESS),
                new AgentEndEvent("r1")
        ));
        when(builder.buildMember(any(), any(), any(Path.class), any(Path.class),
                any(EasyClawTeamClient.class), any(io.agentscope.harness.agent.team.TeamContext.class),
                any())).thenReturn(memberAgent);
        WorkspaceManager wsManager = mock(WorkspaceManager.class);
        when(wsManager.getWorkspace("ws1")).thenReturn(com.xinl.easyclaw.workspace.WorkspaceContext.builder()
                .workspaceId("ws1").userId("u1").name("demo")
                .path(tmp).createdAt(java.time.Instant.now()).lastAccessed(java.time.Instant.now())
                .build());

        SubagentLoader loader = mock(SubagentLoader.class);
        io.agentscope.harness.agent.subagent.SubagentDeclaration decl =
                io.agentscope.harness.agent.subagent.SubagentDeclaration.builder()
                        .name("coder")
                        .description("代码实现专家")
                        .inlineAgentsBody("你是 coder")
                        .build();
        when(loader.loadMerged(any())).thenReturn(List.of(decl));
        TeamRuntimeService svc = new TeamRuntimeService(
                loader, mock(ScenarioResolver.class),
                (d, sid, ctx, client) -> memberAgent, builder, wsManager);

        List<StreamEvent> pushed = new CopyOnWriteArrayList<>();
        svc.setMemberEventSink((sessionId, event) -> {
            pushed.add(event);
            assertEquals("s1", sessionId, "成员事件必须路由到 lead 会话");
        });

        svc.startTeam("ws1", "s1", "alpha", "目标", List.of());
        // lifecycle 回调路径 spawn 成员（对齐 lead 调 team 工具），再唤醒触发成员回合
        svc.doSpawnMember("ws1", "s1", "alpha", "coder", "coder", "");
        svc.wakeMember("alpha", "coder", "请开始干活");
        // 等成员回合结束（wakeup 同步触发 runMemberTurn，Flux 异步消费）
        long deadline = System.currentTimeMillis() + 5000;
        while (pushed.stream().noneMatch(e -> "subagent_end".equals(e.type()))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        List<String> types = pushed.stream().map(StreamEvent::type).toList();
        assertTrue(types.contains("subagent_text"), "文本 delta 应翻译为 subagent_text: " + types);
        assertTrue(types.contains("subagent_tool"), "工具开始应翻译为 subagent_tool: " + types);
        assertTrue(types.contains("subagent_tool_result"), "工具结果应一次性下发: " + types);
        assertTrue(types.contains("subagent_end"), "回合结束应翻译为 subagent_end: " + types);
        // 工具结果累积：两个 delta 拼接后一次下发
        StreamEvent result = pushed.stream()
                .filter(e -> "subagent_tool_result".equals(e.type())).findFirst().orElseThrow();
        assertTrue(String.valueOf(result).contains("任务板空"),
                "工具结果应累积两个 delta: " + result);
    }
}
