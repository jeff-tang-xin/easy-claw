package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OpsServerEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpsServerRepository extends JpaRepository<OpsServerEntity, Long> {

    List<OpsServerEntity> findAllByOrderBySortOrderAscIdAsc();

    /** spoke 下发用：仅启用项，按 sort_order,id 保序。 */
    List<OpsServerEntity> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    boolean existsByServerKey(String serverKey);

    java.util.Optional<OpsServerEntity> findByServerKey(String serverKey);

    /** 分类标签管理（V28）：按标签查引用行（重命名同步）、判存在（删除拦截）。 */
    List<OpsServerEntity> findAllByCategory(String category);

    boolean existsByCategory(String category);
}
