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

    /** 下发过滤批查：该用户在某资源类型上的全部有效授权（一次查询替代逐资源 exists N+1）。 */
    List<ResourceGrantEntity> findByResourceTypeAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
            String resourceType, Long userId, Instant nowFloor, Instant nowCeil);
}
