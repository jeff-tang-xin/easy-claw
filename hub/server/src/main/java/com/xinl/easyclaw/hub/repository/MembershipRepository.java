package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.MembershipEntity;

public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {

    List<MembershipEntity> findByOrgId(Long orgId);

    List<MembershipEntity> findByUserId(Long userId);

    Optional<MembershipEntity> findByOrgIdAndUserId(Long orgId, Long userId);

    boolean existsByOrgIdAndUserId(Long orgId, Long userId);

    long countByOrgId(Long orgId);

    void deleteByOrgIdAndUserId(Long orgId, Long userId);

    /** 删除用户时级联清理其全部组织成员关系（库层无外键，应用层保证一致性）。 */
    void deleteByUserId(Long userId);
}
