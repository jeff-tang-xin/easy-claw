package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.AgentService;
import com.xinl.easyclaw.agent.AgentStateBoxReader;
import com.xinl.easyclaw.agent.SessionTranscriptStore;
import com.xinl.easyclaw.agent.domain.BoxMessage;
import com.xinl.easyclaw.workspace.WorkspaceContext;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 聊天 REST API（React 前端调用）。
 * <p>
 * 流式对话、工具确认与停止均走 WebSocket（{@code /ws/chat}，见 ChatWebSocketHandler）；
 * 本控制器只保留前端刷新/重连时用于恢复界面的两个只读查询。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;
    private final WorkspaceAccessGuard accessGuard;
    private final com.xinl.easyclaw.workspace.WorkspaceFileLayout workspaceFileLayout;

    public ChatController(AgentService agentService,
                          WorkspaceAccessGuard accessGuard,
                          com.xinl.easyclaw.workspace.WorkspaceFileLayout workspaceFileLayout) {
        this.agentService = agentService;
        this.accessGuard = accessGuard;
        this.workspaceFileLayout = workspaceFileLayout;
    }

    /**
     * 会话运行状态：重连/刷新后前端用来恢复 running / pending 状态
     */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String workspaceId, @RequestParam String sessionId) {
        accessGuard.checkSession(workspaceId, sessionId, false);
        return agentService.getSessionStatus(sessionId);
    }

    /**
     * 会话历史：优先读 append-only 转录（transcript.jsonl，不随上下文压缩丢失），
     * 无转录的旧会话回退解析 agent_state.json（模型上下文，压缩后旧消息会被摘要替换）。
     */
    @GetMapping("/history")
    public List<BoxMessage> history(@RequestParam String workspaceId, @RequestParam String sessionId) {
        // history 直接用 sessionId 拼文件系统路径，必须先过字符白名单 + 归属校验，
        // 否则可读取任意工作区任意会话的完整对话记录
        WorkspaceContext ws = accessGuard.checkSession(workspaceId, sessionId, false);
        if (ws == null) {
            return List.of();
        }
        Path sessionDir = workspaceFileLayout.sessionStateDir(
                ws.getPath(), ws.getUserId(), sessionId);
        List<BoxMessage> fromTranscript = SessionTranscriptStore.read(sessionDir);
        if (!fromTranscript.isEmpty()) {
            return fromTranscript;
        }
        return AgentStateBoxReader.read(
                sessionDir.resolve(com.xinl.easyclaw.workspace.WorkspaceFileLayout.AGENT_STATE_FILE));
    }
}
