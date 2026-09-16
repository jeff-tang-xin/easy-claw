package com.xinl.easyclaw.hub.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;

public interface AppKeyProviderBindingRepository extends JpaRepository<AppKeyProviderBindingEntity, Long> {

    List<AppKeyProviderBindingEntity> findByAppKeyId(Long appKeyId);

    /** 列表场景批量取绑定，避免逐 key 查询（N+1）。 */
    List<AppKeyProviderBindingEntity> findByAppKeyIdIn(Collection<Long> appKeyIds);

    boolean existsByProviderId(Long providerId);

    void deleteByAppKeyId(Long appKeyId);
}
