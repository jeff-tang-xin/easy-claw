package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.api.common.streaming.StreamingPromptRunner;
import com.embabel.agent.tools.file.FileTools;
import com.embabel.agent.domain.io.UserInput;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import com.xinl.easyclaw.agent.embabel.domain.UserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Embabel REST 控制器
 * <p>
 * 端点：
 * - POST /api/embabel/chat      → 同步 ChatResult（单回合）
 * - POST /api/embabel/chat/stream → SSE 流式 token 输出（推荐给前端）
 * - GET  /api/embabel/agents    → 列出已注册 Agent
 */
@RestController
@RequestMapping("/api/embabel")
public class EmbabelChatController {

    private static final Logger log = LoggerFactory.getLogger(EmbabelChatController.class);

    private final AgentPlatform agentPlatform;
    private final RoleAgentFactory roleFactory;
    private final String workspaceRoot;
    private final boolean enableWeb;

    public EmbabelChatController(
            AgentPlatform agentPlatform,
            RoleAgentFactory roleFactory,
            @Value("${easy-claw.workspace.root:${user.home}/.easyClaw/workspaces}") String workspaceRoot,
            @Value("${easy-claw.tools.web.enabled:true}") boolean enableWeb) {
        this.agentPlatform = agentPlatform;
        this.roleFactory = roleFactory;
        this.workspaceRoot = workspaceRoot;
        this.enableWeb = enableWeb;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResult chat(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String roleName = request.getOrDefault("role", "");
        String workspaceId = request.getOrDefault("workspaceId", null);

        log.info("Embabel chat: content={}..., role={}, ws={}",
                content.substring(0, Math.min(50, content.length())), roleName, workspaceId);

        var agent = roleFactory.resolveAgent(roleName);
        var process = agentPlatform.createAgentProcessFrom(agent, null,
                new UserInput(content));
        process.run();

        ChatResult result = process.resultOfType(ChatResult.class);
        return result != null ? result : new ChatResult("执行完成", List.of(), "default");
    }

    /**
     * SSE 流式 token 输出
     * <p>
     * 使用 Embabel 原生 PromptRunner.streaming().generateStream() 返回 Flux<String>。
     * 前端按 SSE 协议逐块消费，每块就是一个 token。
     * <p>
     * 注意：当前实现直接走 PromptRunner 流式，不经过 AgentProcess GOAP 规划。
     * 完整 GOAP + 流式 需要通过 AgentPlatform 事件体系桥接，后续迭代。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        String roleName = request.getOrDefault("role", "");
        log.info("Embabel stream chat: role={}, content={}...",
                roleName, content.substring(0, Math.min(40, content.length())));

        var agent = roleFactory.resolveAgent(roleName);
        var fileTools = FileTools.readWrite(workspaceRoot);

        var baseRunner = agentPlatform.platformServices()
                .llm()
                .withDefaultLlm()
                .withSystemPrompt(buildSystemPrompt(roleName));

        baseRunner = baseRunner.withToolObject(fileTools);
        if (enableWeb) {
            baseRunner = baseRunner.withToolGroup(com.embabel.agent.core.CoreToolGroups.WEB);
        }
        baseRunner = baseRunner.withToolGroup(com.embabel.agent.core.CoreToolGroups.MATH);

        var streaming = new StreamingPromptRunnerBuilder(baseRunner).streaming();
        return streaming
                .withPrompt(content)
                .generateStream()
                .map(token -> "data: " + token + "\n\n")
                .doOnComplete(() -> log.info("Stream completed"))
                .doOnError(e -> log.error("Stream error: {}", e.getMessage()));
    }

    @GetMapping("/agents")
    public List<Map<String, String>> listAgents() {
        return agentPlatform.agents().stream()
                .map(a -> Map.of(
                        "name", a.getName(),
                        "class", a.getClass().getSimpleName()
                ))
                .toList();
    }

    private String buildSystemPrompt(String roleName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Easy-Claw 智能助手");
        if (roleName != null && !roleName.isBlank()) {
            sb.append("（角色: ").append(roleName).append("）");
        }
        sb.append("。工作区根目录：").append(workspaceRoot).append("\n\n");
        sb.append("使用可用的工具完成任务。用 Markdown 友好地回答，不要暴露内部工具调用。");
        return sb.toString();
    }
}
