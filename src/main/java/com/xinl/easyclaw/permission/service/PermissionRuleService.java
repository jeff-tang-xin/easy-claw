package com.xinl.easyclaw.permission.service;

import com.xinl.easyclaw.permission.entity.PermissionRuleEntity;
import com.xinl.easyclaw.permission.repository.PermissionRuleRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具权限规则服务（按 Workspace 隔离）
 * <p>
 * 管理用户"永久允许"的持久化规则；workspace_id 为 null 的规则视为全局默认（兼容历史数据）。
 * Agent 构建时注入本 Workspace 的规则（不再询问）。
 */
@Service
public class PermissionRuleService {

    private final PermissionRuleRepository repository;

    public PermissionRuleService(PermissionRuleRepository repository) {
        this.repository = repository;
    }

    /**
     * 指定 Workspace 的"永久允许"工具名集合（含全局默认规则）
     */
    public Set<String> alwaysAllowedTools(String workspaceId) {
        return repository.findAll().stream()
                .filter(r -> "ALLOW".equalsIgnoreCase(r.getBehavior()))
                .filter(r -> r.getWorkspaceId() == null
                        || workspaceId == null
                        || r.getWorkspaceId().equals(workspaceId))
                .map(PermissionRuleEntity::getToolName)
                .collect(Collectors.toSet());
    }

    /**
     * 指定工具在该 Workspace 是否已被永久允许（本 workspace 规则优先，其次全局默认）
     */
    public boolean isAlwaysAllowed(String workspaceId, String toolName) {
        return repository.findByWorkspaceIdAndToolName(workspaceId, toolName)
                .or(() -> repository.findByWorkspaceIdAndToolName(null, toolName))
                .map(r -> "ALLOW".equalsIgnoreCase(r.getBehavior()))
                .orElse(false);
    }

    /**
     * 保存（或更新）一条该 Workspace 的永久允许规则
     */
    public void allow(String workspaceId, String toolName, String source) {
        PermissionRuleEntity entity = repository.findByWorkspaceIdAndToolName(workspaceId, toolName).orElse(null);
        if (entity == null) {
            entity = PermissionRuleEntity.builder()
                    .workspaceId(workspaceId)
                    .toolName(toolName)
                    .behavior("ALLOW")
                    .source(source == null ? "user" : source)
                    .createdAt(Instant.now())
                    .build();
        } else {
            entity.setBehavior("ALLOW");
            entity.setSource(source == null ? "user" : source);
        }
        repository.save(entity);
    }

    /**
     * 移除该 Workspace 的永久允许规则（撤销授权）
     */
    public void remove(String workspaceId, String toolName) {
        repository.deleteByWorkspaceIdAndToolName(workspaceId, toolName);
    }

    /**
     * 该 Workspace 的规则列表（用于 UI 展示授权操作）
     */
    public List<PermissionRuleEntity> findForWorkspace(String workspaceId) {
        return repository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId);
    }
}
