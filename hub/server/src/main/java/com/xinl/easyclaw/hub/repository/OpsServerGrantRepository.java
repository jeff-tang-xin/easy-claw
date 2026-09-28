package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OpsServerGrantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsServerGrantRepository extends JpaRepository<OpsServerGrantEntity, Long> {

    List<OpsServerGrantEntity> findByServerIdOrderByIdAsc(Long serverId);

    Optional<OpsServerGrantEntity> findByServerIdAndUserId(Long serverId, Long userId);

    /** 下发过滤：该用户在该服务器上的有效授权（valid_from &lt;= now &lt;= valid_until）。 */
    boolean existsByServerIdAndUserIdAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
            Long serverId, Long userId, Instant nowFloor, Instant nowCeil);
}
