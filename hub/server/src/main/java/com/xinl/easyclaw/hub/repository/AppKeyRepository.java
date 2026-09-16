package com.xinl.easyclaw.hub.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.AppKeyEntity;

public interface AppKeyRepository extends JpaRepository<AppKeyEntity, Long> {

    List<AppKeyEntity> findByOrgIdOrderByIdDesc(Long orgId);

    /** 网关认证：按 SHA-256 hash 精确命中（key_hash 有唯一约束）。 */
    java.util.Optional<AppKeyEntity> findByKeyHash(String keyHash);
}
