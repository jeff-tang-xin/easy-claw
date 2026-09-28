package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEntity;

public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItemEntity, Long> {

    /** 项目内未删除条目，按最近更新倒序（列表）。 */
    List<KnowledgeItemEntity> findByProjectIdAndStatusOrderByUpdatedAtDesc(Long projectId, String status);

    /** topic 项目内唯一（仅 active）：创建/改名前应用层查重，数据库部分唯一索引兜底并发。 */
    Optional<KnowledgeItemEntity> findByProjectIdAndTopicAndStatus(Long projectId, String topic, String status);

    /**
     * 乐观锁条件更新：仅当条目仍 active 且库内版本等于 expected 时覆盖 topic/summary/content，
     * updated_by 置为当前编辑者，version+1。返回受影响行数（0 即版本冲突或已删）。updated_at 同步推进。
     */
    @Modifying
    @Query("UPDATE KnowledgeItemEntity k SET k.topic = :topic, k.summary = :summary, k.content = :content, "
            + "k.updatedBy = :actorId, k.version = :expected + 1, k.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE k.id = :id AND k.version = :expected AND k.status = 'active'")
    int updateContentIfVersion(Long id, String topic, String summary, String content, Long actorId, Long expected);
}
