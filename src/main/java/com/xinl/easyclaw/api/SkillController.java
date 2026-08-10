package com.xinl.easyclaw.api;

import com.xinl.easyclaw.skill.entity.SkillEntity;
import com.xinl.easyclaw.skill.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<SkillEntity> list(@RequestParam(required = false) String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return skillService.findForWorkspace(workspaceId);
        }
        return skillService.findAll();
    }

    @PostMapping
    public ResponseEntity<SkillEntity> create(@RequestBody SkillEntity entity) {
        SkillEntity saved = skillService.create(entity);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SkillEntity entity) {
        try {
            return ResponseEntity.ok(skillService.update(id, entity));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            skillService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
    }
}
