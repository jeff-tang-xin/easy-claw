package com.xinl.easyclaw.api;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.service.ScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 场景（Scenario）+ 多智能体编排管理接口
 * <p>
 * 场景 = 面向任务形态的智能体运行配置（single 单智能体 / team 多智能体编排）。
 * 激活/停用立即重建对应工作区的 Agent 生效。
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    public List<ScenarioEntity> list() {
        return scenarioService.findAll();
    }

    @PostMapping
    public ScenarioEntity create(@RequestBody ScenarioEntity scenario) {
        return scenarioService.create(scenario);
    }

    @PutMapping("/{id}")
    public ScenarioEntity update(@PathVariable Long id, @RequestBody ScenarioEntity scenario) {
        return scenarioService.update(id, scenario);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        scenarioService.delete(id);
    }

    /** 激活场景（每工作区一个，重复激活即切换），立即重建 Agent 生效 */
    @PostMapping("/{id}/activate/{workspaceId}")
    public ScenarioEntity activate(@PathVariable Long id, @PathVariable String workspaceId) {
        return scenarioService.activate(workspaceId, id);
    }

    /** 停用工作区场景（回到默认主智能体） */
    @PostMapping("/deactivate/{workspaceId}")
    public void deactivate(@PathVariable String workspaceId) {
        scenarioService.deactivate(workspaceId);
    }

    /** 查询工作区当前激活的场景（未激活返回 null） */
    @GetMapping("/active/{workspaceId}")
    public ResponseEntity<ScenarioEntity> active(@PathVariable String workspaceId) {
        return ResponseEntity.ok(scenarioService.activeScenario(workspaceId));
    }

    /** 可用于编排的子 Agent 名单（全局 subagents 目录） */
    @GetMapping("/subagents")
    public List<Map<String, String>> subagents() {
        return scenarioService.availableSubagents();
    }
}
