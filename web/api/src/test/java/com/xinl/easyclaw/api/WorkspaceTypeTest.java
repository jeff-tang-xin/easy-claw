package com.xinl.easyclaw.api;

import com.xinl.easyclaw.agent.spi.AgentRegistry;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.scenario.service.ScenarioService;
import com.xinl.easyclaw.workspace.WorkspaceManager;
import com.xinl.easyclaw.workspace.repository.WorkspaceScenarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区形态分类（single / team / schedule）相关校验的单元测试。
 * <p>
 * 覆盖两类规则：
 * <ul>
 *   <li>{@link WorkspaceManager#normalizeType}：空白归 single，非注册模式直接拒绝</li>
 *   <li>Controller 私有 {@code assertScenarioCompatible}：场景必须存在/启用、
 *       且其 mode 与工作区类型一致（强挂钩）</li>
 * </ul>
 * 项目未引入 Mockito：Repository 用 JDK 动态代理做最小 stub，
 * {@link WorkspaceManager} 构造器仅做字段赋值，传 null 安全。
 */
class WorkspaceTypeTest {

    // ---------- normalizeType ----------

    @Test
    @DisplayName("normalizeType：null / 空白归为 single")
    void blankDefaultsToSingle() {
        assertEquals("single", WorkspaceManager.normalizeType(null));
        assertEquals("single", WorkspaceManager.normalizeType(""));
        assertEquals("single", WorkspaceManager.normalizeType("   "));
    }

    @Test
    @DisplayName("normalizeType：三种已注册模式原样返回（含两端空白裁剪）")
    void registeredModesAccepted() {
        assertEquals("single", WorkspaceManager.normalizeType("single"));
        assertEquals("team", WorkspaceManager.normalizeType("team"));
        assertEquals("schedule", WorkspaceManager.normalizeType("schedule"));
        assertEquals("team", WorkspaceManager.normalizeType("  team  "));
    }

    @Test
    @DisplayName("normalizeType：未注册模式直接抛 IllegalArgumentException")
    void unknownModeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceManager.normalizeType("solo"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceManager.normalizeType("cron"));
    }

    // ---------- assertScenarioCompatible ----------

    private final Map<String, ScenarioEntity> scenarios = new HashMap<>();

    private void seedScenario(String name, String mode, boolean active) {
        ScenarioEntity e = new ScenarioEntity();
        e.setId((long) (scenarios.size() + 1));
        e.setName(name);
        e.setDisplayName(name);
        e.setMode(mode);
        e.setActive(active);
        scenarios.put(name, e);
    }

    /** 用最小 stub 装配出仅绑定 ScenarioService 的 Controller（其余依赖传 null） */
    private WorkspaceController newController() {
        ScenarioRepository repo = stub(ScenarioRepository.class, inv -> switch (inv.method()) {
            case "findByName" -> Optional.ofNullable(scenarios.get((String) inv.arg0()));
            default -> throw new AssertionError("非预期调用: " + inv.method());
        });
        WorkspaceScenarioRepository actRepo = stub(WorkspaceScenarioRepository.class, inv -> {
            throw new AssertionError("校验阶段不应触碰激活表: " + inv.method());
        });
        WorkspaceManager wm = new WorkspaceManager(null, null, null, null, null, null, null, null, null);
        ScenarioService scenarioService = new ScenarioService(repo, actRepo, wm, new AgentRegistry());
        return new WorkspaceController(null, null, null, null, null, scenarioService, null, null);
    }

    private void assertCompatible(WorkspaceController controller, String scenarioName, String workspaceType)
            throws Exception {
        Method m = WorkspaceController.class.getDeclaredMethod(
                "assertScenarioCompatible", String.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(controller, scenarioName, workspaceType);
        } catch (InvocationTargetException e) {
            // 反射调用会把业务异常包一层，拆出真实异常断言
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("类型挂钩：工作区类型与场景 mode 一致时通过")
    void matchingModePasses() {
        seedScenario("general-coding", "single", true);
        seedScenario("team-dev", "team", true);
        seedScenario("nightly-report", "schedule", true);
        WorkspaceController c = newController();

        assertDoesNotThrow(() -> assertCompatible(c, "general-coding", "single"));
        assertDoesNotThrow(() -> assertCompatible(c, "team-dev", "team"));
        assertDoesNotThrow(() -> assertCompatible(c, "nightly-report", "schedule"));
    }

    @Test
    @DisplayName("类型挂钩：工作区类型与场景 mode 不一致时抛异常（跨类型绑定被拒绝）")
    void mismatchedModeRejected() {
        seedScenario("general-coding", "single", true);
        seedScenario("team-dev", "team", true);
        WorkspaceController c = newController();

        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> assertCompatible(c, "general-coding", "team"));
        assertTrue(e1.getMessage().contains("不匹配"), "实际文案: " + e1.getMessage());

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> assertCompatible(c, "team-dev", "schedule"));
        assertTrue(e2.getMessage().contains("不匹配"), "实际文案: " + e2.getMessage());
    }

    @Test
    @DisplayName("类型挂钩：场景不存在或已停用时抛异常")
    void missingOrInactiveRejected() {
        seedScenario("archived", "single", false);
        WorkspaceController c = newController();

        assertThrows(IllegalArgumentException.class,
                () -> assertCompatible(c, "archived", "single"));
        assertThrows(IllegalArgumentException.class,
                () -> assertCompatible(c, "not-exist", "single"));
    }

    // ---------- 动态代理 stub 支持 ----------

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
