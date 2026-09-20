package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.workspace.CreateWorkspaceRequest;
import com.xinl.easyclaw.hub.contract.workspace.UpdateWorkspaceRequest;
import com.xinl.easyclaw.hub.contract.workspace.WorkspaceDto;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区端点（管理面，JWT）：project 面向 spoke 的扩展面，与 project 1:1 绑定。
 * 读/写鉴权跟随绑定 project 的可见性/编辑权限（service 层判定）。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<WorkspaceDto> list(@RequestParam Long orgId) {
        return workspaceService.list(CurrentUserHolder.requireUserId(), orgId);
    }

    @PostMapping
    public WorkspaceDto create(@Valid @RequestBody CreateWorkspaceRequest req) {
        return workspaceService.create(CurrentUserHolder.requireUserId(), req);
    }

    @GetMapping("/{id}")
    public WorkspaceDto get(@PathVariable Long id) {
        return workspaceService.get(CurrentUserHolder.requireUserId(), id);
    }

    @PatchMapping("/{id}")
    public WorkspaceDto update(@PathVariable Long id, @Valid @RequestBody UpdateWorkspaceRequest req) {
        return workspaceService.update(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        workspaceService.archive(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
