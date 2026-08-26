package com.xinl.easyclaw.interceptor.service;

import com.xinl.easyclaw.interceptor.entity.InterceptorConfigEntity;
import com.xinl.easyclaw.interceptor.repository.InterceptorConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 拦截器管理服务实现
 * <p>
 * 提供拦截器的 CRUD 操作，支持权限、审计、限流等拦截器的动态配置
 */
@Service
public class InterceptorManagementServiceImpl implements InterceptorManagementService {

    private static final Logger log = LoggerFactory.getLogger(InterceptorManagementServiceImpl.class);

    private final InterceptorConfigRepository repository;

    public InterceptorManagementServiceImpl(InterceptorConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public InterceptorConfigEntity create(InterceptorConfigEntity config) {
        if (repository.existsByName(config.getName())) {
            throw new IllegalArgumentException("拦截器名称已存在: " + config.getName());
        }
        InterceptorConfigEntity saved = repository.save(config);
        log.info("创建拦截器: name={}, type={}", config.getName(), config.getType());
        return saved;
    }

    @Override
    @Transactional
    public InterceptorConfigEntity update(Long id, InterceptorConfigEntity config) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setOrderNum(config.getOrderNum());
                    existing.setConfig(config.getConfig());
                    existing.setConditions(config.getConditions());
                    if (config.getEnabled() != null) {
                        existing.setEnabled(config.getEnabled());
                    }
                    InterceptorConfigEntity updated = repository.save(existing);
                    log.info("更新拦截器: id={}, name={}", id, existing.getName());
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("拦截器不存在: id=" + id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("拦截器不存在: id=" + id);
        }
        repository.deleteById(id);
        log.info("删除拦截器: id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InterceptorConfigEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InterceptorConfigEntity> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterceptorConfigEntity> findAll() {
        return repository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterceptorConfigEntity> findEnabledInterceptors() {
        return repository.findByEnabledTrueOrderByOrderNumAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterceptorConfigEntity> findByType(String type) {
        return repository.findByTypeOrderByOrderNumAsc(type);
    }

    @Override
    @Transactional
    public InterceptorConfigEntity setEnabled(Long id, boolean enabled) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setEnabled(enabled);
                    InterceptorConfigEntity updated = repository.save(existing);
                    log.info("设置拦截器启用状态: id={}, enabled={}", id, enabled);
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("拦截器不存在: id=" + id));
    }
}
