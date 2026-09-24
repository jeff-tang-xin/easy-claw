package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.CreditConsumptionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditConsumptionRepository extends JpaRepository<CreditConsumptionEntity, Long> {

    /** 使用记录（我的）：多条授权的消耗，按时间倒序分页。 */
    List<CreditConsumptionEntity> findByGrantIdInOrderByIdDesc(Collection<Long> grantIds, Pageable pageable);

    /** 使用记录（组织总览展开单条授权）。 */
    List<CreditConsumptionEntity> findByGrantIdOrderByIdDesc(Long grantId, Pageable pageable);
}
