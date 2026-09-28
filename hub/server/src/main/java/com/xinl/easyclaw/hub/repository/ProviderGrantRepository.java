package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ProviderGrantEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderGrantRepository extends JpaRepository<ProviderGrantEntity, Long> {

    List<ProviderGrantEntity> findByProviderIdOrderByIdAsc(Long providerId);

    /** 某用户的全部授权行（spoke 积分视图：appkey 创建者维度，按 provider 一行）。 */
    List<ProviderGrantEntity> findByUserIdOrderByIdAsc(Long userId);

    /** 多个 provider 的全部授权行（组织/平台积分总览）。 */
    List<ProviderGrantEntity> findByProviderIdInOrderByIdAsc(Collection<Long> providerIds);

    Optional<ProviderGrantEntity> findByProviderIdAndUserId(Long providerId, Long userId);

    /** 授权启用判定：provider 是否配置过任意授权行（有 = 启用授权模式，无 = 开放）。 */
    boolean existsByProviderId(Long providerId);

    void deleteByProviderId(Long providerId);
}
