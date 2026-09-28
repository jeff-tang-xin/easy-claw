package com.xinl.easyclaw.interceptor.service;

import com.xinl.easyclaw.interceptor.entity.InterceptorConfigEntity;

import java.util.List;
import java.util.Optional;

/**
 * 拦截器管理服务接口
 */
public interface InterceptorManagementService {

    InterceptorConfigEntity create(InterceptorConfigEntity config);

    InterceptorConfigEntity update(Long id, InterceptorConfigEntity config);

    void delete(Long id);

    Optional<InterceptorConfigEntity> findById(Long id);

    Optional<InterceptorConfigEntity> findByName(String name);

    List<InterceptorConfigEntity> findAll();

    List<InterceptorConfigEntity> findEnabledInterceptors();

    List<InterceptorConfigEntity> findByType(String type);

    InterceptorConfigEntity setEnabled(Long id, boolean enabled);
}
