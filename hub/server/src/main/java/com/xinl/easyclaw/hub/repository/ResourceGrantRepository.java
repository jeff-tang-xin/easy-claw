package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ResourceGrantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrantEntity, Long> {

    List<ResourceGrantEntity> findByResourceTypeAndResourceIdOrderByIdAsc(String resourceType, Long resourceId);

    Optional<ResourceGrantEntity> findByResourceTypeAndResourceIdAndUserId(
            String resourceType, Long resourceId, Long userId);

    /** 下发过滤：该用户在该资源上的有效授权（valid_from &lt;= now &lt;= valid_until）。 */
    boolean existsByResourceTypeAndResourceIdAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
            String resourceType, Long resourceId, Long userId, Instant nowFloor, Instant nowCeil);
}
