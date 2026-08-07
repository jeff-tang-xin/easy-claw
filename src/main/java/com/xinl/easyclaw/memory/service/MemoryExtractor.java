package com.xinl.easyclaw.memory.service;

import com.xinl.easyclaw.memory.config.MemoryProperties;
import com.xinl.easyclaw.memory.entity.PropositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆提取服务
 * <p>
 * 负责从对话内容中提取命题（偏好、事实、意图、关系），
 * 实现 DICE 模式的事件驱动记忆抽取
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final MemoryService memoryService;
    private final MemoryProperties properties;

    public MemoryExtractor(MemoryService memoryService, MemoryProperties properties) {
        this.memoryService = memoryService;
        this.properties = properties;
    }

    /**
     * 从对话轮次中提取记忆命题
     */
    public int extractFromConversation(String userId, String userMessage, String aiResponse) {
        if (!properties.isExtractionEnabled()) {
            return 0;
        }

        int extracted = 0;

        // 从用户消息中提取意图与偏好
        extracted += extractFromUserMessage(userId, userMessage);

        // 从 AI 响应中提取事实
        extracted += extractFromAIResponse(userId, aiResponse);

        return extracted;
    }

    private int extractFromUserMessage(String userId, String message) {
        int count = 0;

        if (message == null || message.isBlank()) {
            return 0;
        }

        // 提取偏好: "我喜欢"、"我倾向于"、"我希望"
        if (containsAny(message, "喜欢", "倾向于", "偏好", "偏爱", "更爱")) {
            memoryService.remember(userId, message, PropositionType.PREFERENCE, 0.9,
                    List.of("preference"), List.of("user-preference"), null);
            count++;
        }

        // 提取意图: "我想"、"我要"、"我打算"、"帮我"
        if (containsAny(message, "我想", "我要", "我打算", "帮我", "需要", "想让")) {
            memoryService.remember(userId, message, PropositionType.INTENT, 0.85,
                    List.of("intent"), List.of("user-intent"), null);
            count++;
        }

        // 提取事实陈述
        if (containsAny(message, "是", "有", "在", "叫", "用")) {
            memoryService.remember(userId, message, PropositionType.FACT, 0.7,
                    null, List.of("user-fact"), null);
            count++;
        }

        return count;
    }

    private int extractFromAIResponse(String userId, String response) {
        if (response == null || response.isBlank()) {
            return 0;
        }

        // 提取 AI 确认的事实
        memoryService.remember(userId, response, PropositionType.FACT, 0.6,
                null, List.of("ai-response"), null);

        return 1;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
