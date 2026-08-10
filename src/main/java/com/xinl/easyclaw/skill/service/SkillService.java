package com.xinl.easyclaw.skill.service;

import com.xinl.easyclaw.skill.entity.SkillEntity;
import com.xinl.easyclaw.skill.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository repository;

    public SkillService(SkillRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SkillEntity create(SkillEntity entity) {
        if (entity.getScope() == null) entity.setScope("GLOBAL");
        return repository.save(entity);
    }

    @Transactional
    public SkillEntity update(Long id, SkillEntity incoming) {
        return repository.findById(id).map(existing -> {
            if ("SYSTEM".equals(existing.getScope())) {
                throw new IllegalStateException("SYSTEM 级别的 Skill 不可修改: " + existing.getName());
            }
            existing.setDisplayName(incoming.getDisplayName());
            existing.setDescription(incoming.getDescription());
            existing.setContent(incoming.getContent());
            existing.setPromptFragment(incoming.getPromptFragment());
            if (incoming.getEnabled() != null) existing.setEnabled(incoming.getEnabled());
            return repository.save(existing);
        }).orElseThrow(() -> new IllegalArgumentException("Skill 不存在: id=" + id));
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(entity -> {
            if ("SYSTEM".equals(entity.getScope())) {
                throw new IllegalStateException("SYSTEM 级别的 Skill 不可删除: " + entity.getName());
            }
            repository.delete(entity);
            log.info("删除 Skill: id={}, name={}", id, entity.getName());
        });
    }

    public Optional<SkillEntity> findById(Long id) {
        return repository.findById(id);
    }

    public List<SkillEntity> findAll() {
        return repository.findAll();
    }

    /**
     * 聚合查询：SYSTEM + GLOBAL + 指定 workspace 的 WORKSPACE
     */
    public List<SkillEntity> findForWorkspace(String workspaceId) {
        List<SkillEntity> result = new ArrayList<>();
        result.addAll(repository.findByScope("SYSTEM"));
        result.addAll(repository.findByScope("GLOBAL"));
        if (workspaceId != null && !workspaceId.isBlank()) {
            result.addAll(repository.findByScopeAndWorkspaceId("WORKSPACE", workspaceId));
        }
        return result;
    }
}
