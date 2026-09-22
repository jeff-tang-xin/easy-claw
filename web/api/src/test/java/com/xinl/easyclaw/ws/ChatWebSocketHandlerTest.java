package com.xinl.easyclaw.ws;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.api.ToolConfirmValidator;
import com.xinl.easyclaw.api.WorkspaceAccessGuard;
import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.CloudFeatureGate;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件门禁（spec §4.3，WS 入口）：组织经 hub 目录关闭 allow_attachments 后，
 * 带附件消息被拒（error+end，不进 LLM）；本地模式/无快照放行；纯文本消息不受影响。
 */
class ChatWebSocketHandlerTest {

    private static final String CHAT_WITH_ATTACHMENT = """
            {"type":"chat","sessionId":"s1","workspaceId":"w1","message":"hi",
             "attachments":[{"name":"a.png","mimeType":"image/png","base64Data":"AAAA"}]}""";
    private static final String CHAT_TEXT_ONLY =
            "{\"type\":\"chat\",\"sessionId\":\"s1\",\"workspaceId\":\"w1\",\"message\":\"hi\"}";

    private AgentService agentService;
    private CloudBootstrapService cloudBootstrap;
    private ChatWebSocketHandler handler;
    private WebSocketSession connection;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        cloudBootstrap = mock(CloudBootstrapService.class);
        handler = new ChatWebSocketHandler(
                agentService,
                mock(WorkspaceManager.class),
                mock(WorkspaceAccessGuard.class),
                mock(ToolConfirmValidator.class),
                new AgentScopeProperties(),
                new CloudFeatureGate(cloudBootstrap),
                mock(SshConnectionService.class));
        connection = mock(WebSocketSession.class);
        when(connection.getId()).thenReturn("conn-1");
        when(connection.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(connection);
    }

    @Test
    @DisplayName("flag 关：带附件消息被拒（error+end），不进 streamChat")
    void flagOffRejectsAttachments() throws Exception {
        when(cloudBootstrap.snapshot()).thenReturn(snapshot(Map.of("allow_attachments", false)));

        handler.handleTextMessage(connection, new TextMessage(CHAT_WITH_ATTACHMENT));

        verify(agentService, never()).streamChat(any(), any(), any(), anyList(), any(), any(), any(), any());
        String sent = sentPayloads();
        assertTrue(sent.contains("该组织已禁用附件与图片上传"), "应回推门禁错误文案，实际: " + sent);
    }

    @Test
    @DisplayName("本地模式（无快照）：带附件消息放行，进 streamChat")
    void localModeAllowsAttachments() throws Exception {
        when(cloudBootstrap.snapshot()).thenReturn(null);

        handler.handleTextMessage(connection, new TextMessage(CHAT_WITH_ATTACHMENT));

        verify(agentService).streamChat(eq("w1"), eq("s1"), eq("hi"), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("flag 关但消息不带附件：正常放行（门禁只拦附件）")
    void flagOffButTextOnlyPasses() throws Exception {
        when(cloudBootstrap.snapshot()).thenReturn(snapshot(Map.of("allow_attachments", false)));

        handler.handleTextMessage(connection, new TextMessage(CHAT_TEXT_ONLY));

        verify(agentService).streamChat(eq("w1"), eq("s1"), eq("hi"), anyList(), any(), any(), any(), any());
    }

    /** 捕获经装饰器投递到连接的全部事件负载 */
    private String sentPayloads() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(connection, atLeastOnce()).sendMessage(captor.capture());
        StringBuilder sb = new StringBuilder();
        for (TextMessage m : captor.getAllValues()) {
            sb.append(m.getPayload()).append('\n');
        }
        return sb.toString();
    }

    private static CloudBootstrapService.CloudSnapshot snapshot(Map<String, Boolean> flags) {
        return new CloudBootstrapService.CloudSnapshot("Acme", "acme", List.of(), List.of(),
                flags, Set.of(), Instant.now());
    }
}
