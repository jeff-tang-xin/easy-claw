package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OrgFlagSettingEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgFlagSettingRepository extends JpaRepository<OrgFlagSettingEntity, Long> {

    List<OrgFlagSettingEntity> findByOrgId(Long orgId);

    Optional<OrgFlagSettingEntity> findByOrgIdAndFlagId(Long orgId, Long flagId);

    /** 目录项被删除时级联清理其组织开关行（全库无外键，由应用层保证一致性）。 */
    List<OrgFlagSettingEntity> findByFlagIdIn(Collection<Long> flagIds);
}
