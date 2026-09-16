package com.xinl.easyclaw.hub.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, Long> {

    boolean existsByOrgIdIsNullAndSlug(String slug);

    boolean existsByOrgIdAndSlug(Long orgId, String slug);

    /** 平台共享池 + 指定组织集合的 provider（列表/绑定可见范围）。 */
    List<LlmProviderEntity> findByOrgIdIsNullOrOrgIdInOrderById(Collection<Long> orgIds);

    Optional<LlmProviderEntity> findBySlug(String slug);
}
