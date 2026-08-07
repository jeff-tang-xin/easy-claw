package com.xinl.easyclaw.interceptor.repository;

import com.xinl.easyclaw.interceptor.entity.InterceptorConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 拦截器配置数据访问接口
 */
@Repository
public interface InterceptorConfigRepository extends JpaRepository<InterceptorConfigEntity, Long> {

    List<InterceptorConfigEntity> findByEnabledTrueOrderByOrderNumAsc();

    List<InterceptorConfigEntity> findByTypeOrderByOrderNumAsc(String type);

    Optional<InterceptorConfigEntity> findByName(String name);

    boolean existsByName(String name);
}
