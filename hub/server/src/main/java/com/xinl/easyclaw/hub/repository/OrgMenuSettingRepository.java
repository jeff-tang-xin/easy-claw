package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OrgMenuSettingEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgMenuSettingRepository extends JpaRepository<OrgMenuSettingEntity, Long> {

    List<OrgMenuSettingEntity> findByOrgId(Long orgId);

    Optional<OrgMenuSettingEntity> findByOrgIdAndMenuId(Long orgId, Long menuId);

    /** 目录项被删除时级联清理其组织开关行（全库无外键，由应用层保证一致性）。 */
    List<OrgMenuSettingEntity> findByMenuIdIn(Collection<Long> menuIds);
}
