package com.xinl.easyclaw.memory.service;

import com.xinl.easyclaw.memory.config.MemoryProperties;
import com.xinl.easyclaw.memory.entity.MemoryConfigEntity;
import com.xinl.easyclaw.memory.repository.MemoryConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 记忆配置管理服务实现
 * <p>
 * 为每个用户提供独立的记忆配置管理，支持页面实时编辑
 */
@Service
public class MemoryConfigServiceImpl implements MemoryConfigService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfigServiceImpl.class);

    private final MemoryConfigRepository repository;
    private final MemoryProperties properties;

    public MemoryConfigServiceImpl(MemoryConfigRepository repository, MemoryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public MemoryConfigEntity getOrCreate(String userId) {
        return repository.findByUserId(userId)
                .orElseGet(() -> {
                    MemoryConfigEntity config = MemoryConfigEntity.builder()
                            .userId(userId)
                            .extractionEnabled(properties.isExtractionEnabled())
                            .recallLimit(properties.getRecallLimit())
                            .forgetDays(properties.getForgetDays())
                            .minReferenceCount(properties.getMinReferenceCount())
                            .recencyWeight(properties.getWeights().getRecency())
                            .contentWeight(properties.getWeights().getContent())
                            .contextWeight(properties.getWeights().getContext())
                            .emotionalWeight(properties.getWeights().getEmotional())
                            .associativeWeight(properties.getWeights().getAssociative())
                            .insightWeight(properties.getWeights().getInsight())
                            .build();
                    MemoryConfigEntity saved = repository.save(config);
                    log.info("创建用户记忆配置: userId={}", userId);
                    return saved;
                });
    }

    @Override
    @Transactional
    public MemoryConfigEntity update(String userId, MemoryConfigEntity config) {
        return repository.findByUserId(userId)
                .map(existing -> {
                    existing.setExtractionEnabled(config.getExtractionEnabled());
                    existing.setRecallLimit(config.getRecallLimit());
                    existing.setForgetDays(config.getForgetDays());
                    existing.setMinReferenceCount(config.getMinReferenceCount());
                    existing.setRecencyWeight(config.getRecencyWeight());
                    existing.setContentWeight(config.getContentWeight());
                    existing.setContextWeight(config.getContextWeight());
                    existing.setEmotionalWeight(config.getEmotionalWeight());
                    existing.setAssociativeWeight(config.getAssociativeWeight());
                    existing.setInsightWeight(config.getInsightWeight());
                    MemoryConfigEntity updated = repository.save(existing);
                    log.info("更新用户记忆配置: userId={}", userId);
                    return updated;
                })
                .orElseThrow(() -> new IllegalArgumentException("用户记忆配置不存在: userId=" + userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryConfigEntity> findByUserId(String userId) {
        return repository.findByUserId(userId);
    }

    @Override
    @Transactional
    public void delete(String userId) {
        if (!repository.existsByUserId(userId)) {
            throw new IllegalArgumentException("用户记忆配置不存在: userId=" + userId);
        }
        repository.deleteByUserId(userId);
        log.info("删除用户记忆配置: userId={}", userId);
    }

    @Override
    @Transactional
    public void resetToDefault(String userId) {
        MemoryConfigEntity defaults = MemoryConfigEntity.builder()
                .userId(userId)
                .extractionEnabled(properties.isExtractionEnabled())
                .recallLimit(properties.getRecallLimit())
                .forgetDays(properties.getForgetDays())
                .minReferenceCount(properties.getMinReferenceCount())
                .recencyWeight(properties.getWeights().getRecency())
                .contentWeight(properties.getWeights().getContent())
                .contextWeight(properties.getWeights().getContext())
                .emotionalWeight(properties.getWeights().getEmotional())
                .associativeWeight(properties.getWeights().getAssociative())
                .insightWeight(properties.getWeights().getInsight())
                .build();
        repository.findByUserId(userId).ifPresent(existing -> {
            defaults.setId(existing.getId());
            repository.save(defaults);
        });
        log.info("重置用户记忆配置为默认值: userId={}", userId);
    }
}
