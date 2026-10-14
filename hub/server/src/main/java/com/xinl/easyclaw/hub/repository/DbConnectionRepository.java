package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.DbConnectionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DbConnectionRepository extends JpaRepository<DbConnectionEntity, Long> {

    List<DbConnectionEntity> findAllByOrderBySortOrderAscIdAsc();

    /** spoke 下发用：仅启用项，按 sort_order,id 保序。 */
    List<DbConnectionEntity> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    boolean existsByServerKey(String serverKey);

    java.util.Optional<DbConnectionEntity> findByServerKey(String serverKey);

    /** authorize-check 批查：一次取回全部候选 serverKey 的行（替代逐 key N+1 查询）。 */
    List<DbConnectionEntity> findByServerKeyIn(Collection<String> serverKeys);
}
