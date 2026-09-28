package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.workspace.entity.WorkspaceScenarioEntity;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ScenarioResolver} 单元测试。
 * <p>
 * 项目未引入 Mockito，这里用 JDK 动态代理为两个 Spring Data 接口做最小 stub：
 * 只实现被测路径真正调用的方法（{@code findByWorkspaceId} / {@code findById}），
 * 其余方法一旦被调用即抛异常，从而顺带验证「不多打一次库」。
 */
class ScenarioResolverTest {

    private static final String WS = "ws-1";

    /** 记录激活关系表的返回；key = workspaceId */
    private final Map<String, WorkspaceScenarioEntity> activations = new HashMap<>();
    /** 记录场景定义表的返回；key = scenarioId */
    private final Map<Long, ScenarioEntity> scenarios = new HashMap<>();
    /** 置位后，对应仓库的查询抛异常，用于验证降级 */
    private boolean activationBoom;
    private boolean scenarioBoom;

    private ScenarioResolver newResolver() {
        WorkspaceScenarioRepository actRepo = stub(WorkspaceScenarioRepository.class, invocation -> {
            if (!"findByWorkspaceId".equals(invocation.method())) {
                throw new AssertionError("非预期调用: " + invocation.method());
            }
            if (activationBoom) {
                throw new IllegalStateException("表结构异常");
            }
            return Optional.ofNullable(activations.get((String) invocation.arg0()));
        });
        ScenarioRepository scenarioRepo = stub(ScenarioRepository.class, invocation -> {
            if (!"findById".equals(invocation.method())) {
                throw new AssertionError("非预期调用: " + invocation.method());
            }
            if (scenarioBoom) {
                throw new IllegalStateException("场景表异常");
            }
            return Optional.ofNullable(scenarios.get((Long) invocation.arg0()));
        });
        return new ScenarioResolver(scenarioRepo, actRepo);
    }

    private ScenarioEntity scenario(long id, String mode, boolean active, String workflow) {
        ScenarioEntity e = new ScenarioEntity();
        e.setId(id);
        e.setName("s-" + id);
        e.setMode(mode);
        e.setActive(active);
        e.setWorkflow(workflow);
        return e;
    }

    /** 把「工作区已激活 id 号场景」写入两张 stub 表 */
    private ScenarioEntity activate(long id, String mode, boolean active, String workflow) {
        ScenarioEntity s = scenario(id, mode, active, workflow);
        scenarios.put(id, s);
        activations.put(WS, WorkspaceScenarioEntity.builder().workspaceId(WS).scenarioId(id).build());
        return s;
    }

    // ---------- activeScenario ----------

    @Test
    @DisplayName("activeScenario：已激活且启用的场景正常返回")
    void activeScenarioReturnsEnabled() {
        ScenarioEntity s = activate(7L, "team", true, "{}");
        assertEquals(s, newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：工作区未激活任何场景时返回 null")
    void activeScenarioNullWhenNotActivated() {
        assertNull(newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：激活关系存在但场景已被删除时返回 null")
    void activeScenarioNullWhenScenarioDeleted() {
        activations.put(WS, WorkspaceScenarioEntity.builder().workspaceId(WS).scenarioId(99L).build());
        assertNull(newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：场景已停用（active=false）时返回 null")
    void activeScenarioNullWhenDisabled() {
        activate(7L, "team", false, "{}");
        assertNull(newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：active 为 null 视为停用，不抛 NPE")
    void activeScenarioNullSafeOnNullActive() {
        ScenarioEntity s = scenario(7L, "team", true, "{}");
        s.setActive(null);
        scenarios.put(7L, s);
        activations.put(WS, WorkspaceScenarioEntity.builder().workspaceId(WS).scenarioId(7L).build());
        assertNull(newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：激活关系查询抛异常时降级为无场景")
    void activeScenarioDegradesOnActivationFailure() {
        activationBoom = true;
        assertNull(newResolver().activeScenario(WS));
    }

    @Test
    @DisplayName("activeScenario：场景定义查询抛异常时降级为无场景")
    void activeScenarioDegradesOnScenarioFailure() {
        activate(7L, "team", true, "{}");
        scenarioBoom = true;
        assertNull(newResolver().activeScenario(WS));
    }

    // ---------- activeWorkflowJson ----------

    @Test
    @DisplayName("activeWorkflowJson：team 模式返回工作流 JSON 原文")
    void workflowJsonForTeamMode() {
        activate(7L, "team", true, "{\"steps\":[]}");
        assertEquals("{\"steps\":[]}", newResolver().activeWorkflowJson(WS));
    }

    @Test
    @DisplayName("activeWorkflowJson：single 模式即使配了工作流也返回 null")
    void workflowJsonNullForSingleMode() {
        activate(7L, "single", true, "{\"steps\":[]}");
        assertNull(newResolver().activeWorkflowJson(WS));
    }

    @Test
    @DisplayName("activeWorkflowJson：mode 为 null 时返回 null，不抛 NPE")
    void workflowJsonNullSafeOnNullMode() {
        activate(7L, null, true, "{\"steps\":[]}");
        assertNull(newResolver().activeWorkflowJson(WS));
    }

    @Test
    @DisplayName("activeWorkflowJson：未激活场景时返回 null")
    void workflowJsonNullWhenNoScenario() {
        assertNull(newResolver().activeWorkflowJson(WS));
    }

    @Test
    @DisplayName("activeWorkflowJson：team 模式但未配工作流时返回 null")
    void workflowJsonNullWhenTeamWithoutWorkflow() {
        activate(7L, "team", true, null);
        assertNull(newResolver().activeWorkflowJson(WS));
    }

    @Test
    @DisplayName("activeWorkflowJson：底层查询异常时降级为 null（不向上抛）")
    void workflowJsonDegradesOnFailure() {
        activationBoom = true;
        assertNull(newResolver().activeWorkflowJson(WS));
    }

    // ---------- 动态代理 stub 支持 ----------

    /** 一次接口调用的方法名与首个参数 */
    private record Invocation(String method, Object arg0) {
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface, Function<Invocation, Object> handler) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return iface.getSimpleName() + "$Stub";
                    }
                    Object arg0 = (args == null || args.length == 0) ? null : args[0];
                    return handler.apply(new Invocation(method.getName(), arg0));
                });
    }
}
