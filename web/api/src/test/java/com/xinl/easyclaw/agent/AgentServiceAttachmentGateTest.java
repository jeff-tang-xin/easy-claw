package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.agent.domain.StreamEvent;
import com.xinl.easyclaw.agent.domain.UserAttachment;
import com.xinl.easyclaw.agent.spi.OrchestratorRegistry;
import com.xinl.easyclaw.config.AgentFactory;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.CloudFeatureGate;
import com.xinl.easyclaw.permission.service.PermissionRuleService;
import com.xinl.easyclaw.workspace.ScenarioResolver;
import com.xinl.easyclaw.workspace.WorkspaceFileLayout;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * streamChat 附件门禁兜底（spec §4.3）：WS 入口已拦，此处防绕过 WS 的其他调用方。
 * flag 关 + 带附件 → 同步回 onError（门禁文案）+ onFinish，不进入后续流程。
 */
class AgentServiceAttachmentGateTest {

    @Test
    @DisplayName("flag 关：streamChat 带附件同步回错误并结束回合")
    void streamChatRejectsAttachmentsWhenFlagOff() {
        CloudBootstrapService bootstrap = mock(CloudBootstrapService.class);
        when(bootstrap.snapshot()).thenReturn(new CloudBootstrapService.CloudSnapshot(
                "Acme", "acme", List.of(), List.of(),
                Map.of("allow_attachments", false), java.util.Set.of(),
                List.of(), List.of(), List.of(), Instant.now()));
        AgentService service = new AgentService(
                mock(WorkspaceManager.class),
                mock(AgentFactory.class),
                mock(PermissionRuleService.class),
                new AgentScopeProperties(),
                new SessionRegistry(),
                mock(ScenarioResolver.class),
                new WorkspaceFileLayout(),
                new OrchestratorRegistry(),
                new CloudFeatureGate(bootstrap));

        List<Throwable> errors = new ArrayList<>();
        List<StreamEvent> events = new ArrayList<>();
        AtomicBoolean finished = new AtomicBoolean();

        service.streamChat("w1", "s1", "hi",
                List.of(new UserAttachment("a.png", "image/png", "AAAA")),
                events::add,
                errors::add,
                () -> finished.set(true));

        assertEquals(1, errors.size(), "应恰好回一次错误");
        assertTrue(errors.get(0).getMessage().contains("该组织已禁用附件与图片上传"),
                "错误文案应为门禁文案，实际: " + errors.get(0).getMessage());
        assertTrue(events.isEmpty(), "拒绝时不应产生任何流事件");
        assertTrue(finished.get(), "必须回调 onFinish 结束回合");
    }
}
