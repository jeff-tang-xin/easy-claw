package com.xinl.easyclaw.agent.team;

import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EasyClawTeamClient 的协议层验证：任务板流转（委托 LocalTeamClient 的 CAS 语义）、
 * spawn/shutdown 簿记、计划审批状态机。
 */
class EasyClawTeamClientTest {

    @TempDir
    Path tmp;

    private FileBaseStore store() {
        return new FileBaseStore(tmp);
    }

    private static EasyClawTeamClient.MemberLifecycle noop() {
        return new EasyClawTeamClient.MemberLifecycle() {
            @Override
            public void spawn(String ns, String tn, String n, String r, String p) { }

            @Override
            public void shutdown(String ns, String tn, String n) { }
        };
    }

    @Test
    void taskLifecycleCreateClaimComplete() {
        FileBaseStore store = store();
        EasyClawTeamClient client = new EasyClawTeamClient(store, noop());
        client.createTeam(new TeamCreateSpec("alpha", "ws1", "demo", "main", "",
                List.of(new TeamMemberSpec("w1", "w1", "", "local")))).block();

        TeamTask created = client.createTask("ws1", "alpha", "实现 X", "描述", List.of(), "").block();
        assertEquals("task-1", created.taskId());
        assertEquals(TeamTask.PENDING, created.state());

        TeamTask claimed = client.claimTask("ws1", "alpha", "task-1", "w1", 0L).block();
        assertEquals(TeamTask.IN_PROGRESS, claimed.state());
        assertEquals("w1", claimed.owner());

        TeamTask done = client.completeTask("ws1", "alpha", "task-1", "搞定了").block();
        assertEquals(TeamTask.COMPLETED, done.state());
        assertEquals("搞定了", done.result());

        // 完成后不可再领
        assertThrows(Exception.class,
                () -> client.claimTask("ws1", "alpha", "task-1", "w2", 0L).block());
    }

    @Test
    void spawnMemberRegistersRosterRowAndFiresLifecycle() {
        FileBaseStore store = store();
        List<String> spawned = new CopyOnWriteArrayList<>();
        EasyClawTeamClient client = new EasyClawTeamClient(store, new EasyClawTeamClient.MemberLifecycle() {
            @Override
            public void spawn(String ns, String tn, String n, String r, String p) { spawned.add(n); }

            @Override
            public void shutdown(String ns, String tn, String n) { }
        });
        client.createTeam(new TeamCreateSpec("alpha", "ws1", "demo", "main", "", List.of())).block();

        client.spawnMember("ws1", "alpha", "coder", "coder", "写代码的").block();

        assertEquals(List.of("coder"), spawned, "lifecycle 回调应被触发");
        var row = store.get(List.of("teams", "ws1", "alpha", "members"), "coder");
        assertNotNull(row, "成员表应落 store");
        assertEquals("Working", row.value().get("status"));

        // shutdown 更新状态并触发回调
        List<String> shutdown = new CopyOnWriteArrayList<>();
        EasyClawTeamClient client2 = new EasyClawTeamClient(store, new EasyClawTeamClient.MemberLifecycle() {
            @Override
            public void spawn(String ns, String tn, String n, String r, String p) { }

            @Override
            public void shutdown(String ns, String tn, String n) { shutdown.add(n); }
        });
        client2.shutdownMember("ws1", "alpha", "coder").block();
        assertEquals(List.of("coder"), shutdown);
        assertEquals("Offline", store.get(List.of("teams", "ws1", "alpha", "members"), "coder")
                .value().get("status"));
    }

    @Test
    void planApprovalFlowUpdatesStateAndNotifies() {
        FileBaseStore store = store();
        EasyClawTeamClient client = new EasyClawTeamClient(store, noop());
        client.createTeam(new TeamCreateSpec("alpha", "ws1", "demo", "main", "",
                List.of(new TeamMemberSpec("w1", "w1", "", "local")))).block();

        client.submitPlan("ws1", "alpha", "w1", "分三步走").block();
        var plan = store.search(List.of("teams", "ws1", "alpha", "plans"), 10, 0).get(0);
        assertEquals("PENDING", plan.value().get("state"));
        assertEquals("分三步走", plan.value().get("planText"));

        client.approvePlan("ws1", "alpha", "w1").block();
        assertEquals("APPROVED", store.get(List.of("teams", "ws1", "alpha", "plans"),
                plan.key()).value().get("state"));

        // 邮箱应有审批通知（submitPlan 唤醒 lead 的消息 + approve 回给成员的消息）
        var msgs = client.listMessages("ws1", "alpha", 50).block();
        assertTrue(msgs.size() >= 2, "审批流应产生邮箱消息，实际=" + msgs.size());
    }

    @Test
    void sendMessageToSelfIsRejected() {
        EasyClawTeamClient client = new EasyClawTeamClient(store(), noop());
        assertThrows(Exception.class,
                () -> client.sendMessage("ws1", "alpha", "lead", "lead", "自言自语").block());
    }
}
