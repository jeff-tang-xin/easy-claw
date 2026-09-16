package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.contract.project.CreateProjectRequest;
import com.xinl.easyclaw.hub.contract.project.ProjectDto;
import com.xinl.easyclaw.hub.contract.project.UpdateProjectRequest;
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
import com.xinl.easyclaw.hub.service.ProjectService;

/**
 * 项目端点（§6.3）。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectDto> list(@RequestParam Long orgId) {
        return projectService.list(CurrentUserHolder.requireUserId(), orgId);
    }

    @PostMapping
    public ProjectDto create(@Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(CurrentUserHolder.requireUserId(), req);
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable Long id) {
        return projectService.get(CurrentUserHolder.requireUserId(), id);
    }

    @PatchMapping("/{id}")
    public ProjectDto update(@PathVariable Long id, @Valid @RequestBody UpdateProjectRequest req) {
        return projectService.update(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        projectService.archive(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long id) {
        projectService.restore(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
