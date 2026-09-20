package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.KnowledgeItemEventEntity;

public interface KnowledgeItemEventRepository extends JpaRepository<KnowledgeItemEventEntity, Long> {

    /** 历史列表：版本倒序（最新在前）。 */
    List<KnowledgeItemEventEntity> findByItemIdOrderByVersionDesc(Long itemId);

    /** 读任意历史版本全文。 */
    Optional<KnowledgeItemEventEntity> findByItemIdAndVersion(Long itemId, Long version);
}
