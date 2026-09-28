package com.xinl.easyclaw.scenario.service;

import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ScenarioService} 对能力绑定四列（skills / subagents / mcpServices / capabilityTier）
 * 的读写测试。
 * <p>
 * 这四列是后来加的，而 {@code update} 是逐字段手工拷贝的写法 —— 漏拷一个字段不会编译报错、
 * 不会抛异常、日志也照常打印「更新场景成功」，但用户在界面上保存的绑定<b>根本没进库</b>。
 * 这正是本轮实际踩到的 bug，因此这里逐字段断言，而不是只测一个代表字段。
 */
class ScenarioServiceBindingTest {

    private ScenarioRepository scenarioRepo;
    private ScenarioService service;

    @BeforeEach
    void setUp() {
        scenarioRepo = mock(ScenarioRepository.class);
        WorkspaceScenarioRepository activationRepo = mock(WorkspaceScenarioRepository.class);
        when(activationRepo.findByScenarioId(anyLong())).thenReturn(List.of());
        // save 回显入参，让断言直接检查被持久化的那个实体
        when(scenarioRepo.save(any(ScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new ScenarioService(scenarioRepo, activationRepo,
                mock(WorkspaceManager.class), new AgentRegistry());
    }

    private ScenarioEntity existing() {
        ScenarioEntity e = new ScenarioEntity();
        e.setId(1L);
        e.setName("dev");
        e.setMode("single");
        when(scenarioRepo.findById(1L)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void update应完整写入四个能力绑定字段() {
        existing();
        ScenarioEntity patch = new ScenarioEntity();
        patch.setSkills("[\"clean-code\"]");
        patch.setSubagents("[\"coder\"]");
        patch.setMcpServices("[\"fs\"]");
        patch.setCapabilityTier("readonly");

        ScenarioEntity updated = service.update(1L, patch);

        assertThat(updated.getSkills()).isEqualTo("[\"clean-code\"]");
        assertThat(updated.getSubagents()).isEqualTo("[\"coder\"]");
        assertThat(updated.getMcpServices()).isEqualTo("[\"fs\"]");
        assertThat(updated.getCapabilityTier()).isEqualTo("readonly");
    }

    /** 界面上取消全部勾选会提交 ""，必须真的解绑；若按 null 跳过处理则永远无法清空 */
    @Test
    void update传空串应解除绑定并归一为null() {
        ScenarioEntity e = existing();
        e.setSkills("[\"clean-code\"]");
        e.setMcpServices("[\"fs\"]");
        e.setCapabilityTier("readonly");

        ScenarioEntity patch = new ScenarioEntity();
        patch.setSkills("");
        patch.setMcpServices("   ");
        patch.setCapabilityTier("");

        ScenarioEntity updated = service.update(1L, patch);

        assertThat(updated.getSkills()).isNull();
        assertThat(updated.getMcpServices()).isNull();
        assertThat(updated.getCapabilityTier()).isNull();
    }

    /** patch 里没提到的字段属于「不修改」，不能被顺手清掉 */
    @Test
    void update未提供的绑定字段应保持原值() {
        ScenarioEntity e = existing();
        e.setSkills("[\"clean-code\"]");
        e.setCapabilityTier("readonly");

        ScenarioEntity patch = new ScenarioEntity();
        patch.setDisplayName("开发");

        ScenarioEntity updated = service.update(1L, patch);

        assertThat(updated.getSkills()).isEqualTo("[\"clean-code\"]");
        assertThat(updated.getCapabilityTier()).isEqualTo("readonly");
        assertThat(updated.getDisplayName()).isEqualTo("开发");
    }

    @Test
    void create应把空白绑定归一为null() {
        when(scenarioRepo.existsByName("new")).thenReturn(false);
        ScenarioEntity s = new ScenarioEntity();
        s.setName("new");
        s.setSkills("");
        s.setSubagents("   ");
        s.setMcpServices("");
        s.setCapabilityTier("  ");

        ScenarioEntity created = service.create(s);

        assertThat(created.getSkills()).isNull();
        assertThat(created.getSubagents()).isNull();
        assertThat(created.getMcpServices()).isNull();
        assertThat(created.getCapabilityTier()).isNull();
    }

    @Test
    void create应保留有效的能力绑定字段() {
        when(scenarioRepo.existsByName("new")).thenReturn(false);
        ScenarioEntity s = new ScenarioEntity();
        s.setName("new");
        s.setMcpServices("[\"fs\"]");
        s.setCapabilityTier("full");

        ScenarioEntity created = service.create(s);

        assertThat(created.getMcpServices()).isEqualTo("[\"fs\"]");
        assertThat(created.getCapabilityTier()).isEqualTo("full");
    }

    /**
     * 方案 C：前端智能体下拉改走 {@code GET /api/scenarios/agents}（取代已删除的 /api/roles）。
     * 关键差异是<b>必须含 main 主控</b>（availableSubagents 刻意排除 main），且要带展示名。
     */
    @Test
    void allAgents应包含main并带展示名与主控标记() {
        List<java.util.Map<String, String>> agents = service.allAgents();

        assertThat(agents).hasSize(8);
        List<String> ids = agents.stream().map(a -> a.get("agentId")).toList();
        assertThat(ids).contains("main", "coder", "reviewer", "planner",
                "researcher", "code-expert", "file-expert", "ops");

        java.util.Map<String, String> main = agents.stream()
                .filter(a -> "main".equals(a.get("agentId"))).findFirst().orElseThrow();
        assertThat(main.get("main")).isEqualTo("true");
        assertThat(main.get("displayName")).isNotBlank();

        // 其余成员一律标 main=false，避免前端误把成员当主控
        for (java.util.Map<String, String> a : agents) {
            assertThat(a).containsKeys("agentId", "displayName", "description", "icon", "main");
            if (!"main".equals(a.get("agentId"))) {
                assertThat(a.get("main")).isEqualTo("false");
                assertThat(a.get("displayName")).isNotBlank();
            }
        }
    }
}
