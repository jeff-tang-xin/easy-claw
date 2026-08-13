package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.role.service.RoleManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 声明加载器（Stub - pending Embabel migration）
 * <p>
 * 原实现依赖 {@code io.agentscope.harness.agent.subagent.SubagentDeclaration}，
 * 该类已随 AgentScope 一并移除。当前返回空列表，
 * 待 Embabel 迁移完成后恢复实际加载逻辑。
 */
@Component
public class SubagentLoader {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoader.class);

    private final RoleManagementService roleService;

    public SubagentLoader(RoleManagementService roleService) {
        this.roleService = roleService;
    }

    /**
     * 合并加载全局 + Workspace 两级子 Agent 声明。
     * TODO: migrate to Embabel - SubagentDeclaration type removed, returns empty list for now.
     */
    public List<Object> loadMerged(Path globalDir, Path workspaceDir) {
        log.info("loadMerged() called (no-op pending Embabel migration)");
        return new ArrayList<>();
    }

    /**
     * 扫描目录下的子 Agent 声明文件。
     * TODO: migrate to Embabel - SubagentDeclaration type removed, returns empty list for now.
     */
    public List<Object> loadFromDirectory(Path subagentsDir) {
        log.info("loadFromDirectory() called (no-op pending Embabel migration): {}", subagentsDir);
        return new ArrayList<>();
    }
}
