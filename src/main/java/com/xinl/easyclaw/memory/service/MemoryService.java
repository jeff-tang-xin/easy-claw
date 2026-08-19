package com.xinl.easyclaw.memory.service;

import com.xinl.easyclaw.memory.entity.PropositionEntity;
import com.xinl.easyclaw.memory.entity.PropositionType;

import java.time.Instant;
import java.util.List;

/**
 * 长期记忆服务 - 负责命题的提取、召回、遗忘
 */
public interface MemoryService {

    /**
     * 存储一条命题到长期记忆
     *
     * @param userId        用户 ID
     * @param content       命题内容
     * @param type          命题类型
     * @param confidence    置信度 (0.0 - 1.0)
     * @param emotionTags   情感标签
     * @param topics        话题标签
     * @param conversationId 关联对话 ID
     * @return 持久化后的命题实体
     */
    PropositionEntity remember(
            String userId,
            String workspaceId,
            String content,
            PropositionType type,
            Double confidence,
            List<String> emotionTags,
            List<String> topics,
            String conversationId
    );

    /**
     * 召回用户与指定内容相关的记忆
     *
     * @param userId    用户 ID
     * @param queryText 查询文本（用于关键词匹配）
     * @param topics    话题过滤（可选，为空则不限话题）
     * @return 按相关性排序的命题列表，数量不超过 recallLimit
     */
    List<PropositionEntity> recall(String userId, String workspaceId, String queryText, List<String> topics);

    /**
     * 更新记忆的访问时间与引用计数（标记为"被使用过"）
     *
     * @param propositionId 命题 ID
     */
    void touch(Long propositionId);

    /**
     * 执行遗忘机制：清理超过 forgetDays 天且引用次数低于 minReferenceCount 的记忆
     *
     * @param userId 用户 ID
     * @return 被清理的记忆数量
     */
    int forget(String userId, String workspaceId);

    /**
     * 统计用户记忆总数
     *
     * @param userId 用户 ID
     * @return 记忆条数
     */
    long count(String userId, String workspaceId);

    /**
     * 查询用户在某时间点之后新增的记忆
     *
     * @param userId 用户 ID
     * @param since  起始时间
     * @return 命题列表
     */
    List<PropositionEntity> findRecent(String userId, String workspaceId, Instant since);

    /**
     * 查询用户所有记忆
     *
     * @param userId 用户 ID
     * @return 命题列表
     */
    List<PropositionEntity> findByUserId(String userId, String workspaceId);

    /**
     * 根据 ID 删除记忆
     *
     * @param id 命题 ID
     */
    void deleteById(Long id);

    // ===== 旧签名兼容（不传 workspaceId 时视为不过滤） =====

    default PropositionEntity remember(
            String userId,
            String content,
            PropositionType type,
            Double confidence,
            List<String> emotionTags,
            List<String> topics,
            String conversationId
    ) {
        return remember(userId, null, content, type, confidence, emotionTags, topics, conversationId);
    }

    default List<PropositionEntity> recall(String userId, String queryText, List<String> topics) {
        return recall(userId, null, queryText, topics);
    }

    default int forget(String userId) {
        return forget(userId, null);
    }

    default long count(String userId) {
        return count(userId, null);
    }

    default List<PropositionEntity> findRecent(String userId, Instant since) {
        return findRecent(userId, null, since);
    }

    default List<PropositionEntity> findByUserId(String userId) {
        return findByUserId(userId, null);
    }

}
