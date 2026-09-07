package com.xinl.easyclaw.scenario.service;

import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import com.xinl.easyclaw.workspace.WorkspaceManager;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScenarioService#activateByName} 单元测试。
 * <p>
 * 该方法是「新建/编辑工作区时必选场景」的落地点：前端提交的是稳定标识名
 * （如 {@code general-coding}）而非自增主键，因此必须保证按名解析、停用场景
 * 不可被激活、非法入参不打库。
 * <p>
 * 沿用 {@code ScenarioResolverTest} 的做法：项目未引入 Mockito，用 JDK 动态代理
 * 为两个 Spring Data 接口做最小 stub；{@link WorkspaceManager} 是具体类，
 * 以匿名子类覆写 {@code rebuildAgent} 记录调用（其构造函数仅做字段赋值，传 null 安全）。
 */
class ScenarioServiceActivateByNameTest {

    private static final String WS = "ws-1";

    /** 场景定义表：key = name */
    private final Map<String, ScenarioEntity> byName = new HashMap<>();
    /** 场景定义表：key = id */
    private final Map<Long, ScenarioEntity> byId = new HashMap<>();
    /** 激活关系表落库结果 */
    private final Map<String, Long> saved = new HashMap<>();
    /** 记录 Agent 重建次数 */
    private int rebuilds;

    private ScenarioService newService() {
        ScenarioRepository scenarioRepo = stub(ScenarioRepository.class, inv -> switch (inv.method()) {
            case "findByName" -> Optional.ofNullable(byName.get((String) inv.arg0()));
            case "findById" -> Optional.ofNullable(byId.get((Long) inv.arg0()));
            default -> throw new AssertionError("非预期调用: " + inv.method());
        });
        WorkspaceScenarioRepository actRepo = stub(WorkspaceScenarioRepository.class, inv -> switch (inv.method()) {
            case "findByWorkspaceId" -> Optional.empty();
            case "save" -> {
                WorkspaceScenarioEntity e = (WorkspaceScenarioEntity) inv.arg0();
                saved.put(e.getWorkspaceId(), e.getScenarioId());
                yield e;
            }
            default -> throw new AssertionError("非预期调用: " + inv.method());
        });
        WorkspaceManager wm = new WorkspaceManager(null, null, null, null, null, null, null, null) {
            @Override
            public void rebuildAgent(String workspaceId) {
                rebuilds++;
            }
        };
        return new ScenarioService(scenarioRepo, actRepo, wm);
    }

    private void seed(String name, long id, boolean active) {
        ScenarioEntity e = new ScenarioEntity();
        e.setId(id);
        e.setName(name);
        e.setDisplayName(name);
        e.setActive(active);
        byName.put(name, e);
        byId.put(id, e);
    }

    @Test
    @DisplayName("按名激活：命中启用场景时写入绑定并重建 Agent")
    void activatesByStableName() {
        seed("general-coding", 3L, true);

        Optional<ScenarioEntity> got = newService().activateByName(WS, "general-coding");

        assertTrue(got.isPresent());
        assertEquals(3L, got.get().getId());
        assertEquals(3L, saved.get(WS));
        assertEquals(1, rebuilds);
    }

    @Test
    @DisplayName("按名激活：场景不存在时返回空且不写库（调用方可回退默认场景）")
    void missingScenarioYieldsEmpty() {
        assertTrue(newService().activateByName(WS, "not-exist").isEmpty());
        assertNull(saved.get(WS));
        assertEquals(0, rebuilds);
    }

    @Test
    @DisplayName("按名激活：已停用场景不可被激活")
    void inactiveScenarioRejected() {
        seed("archived", 9L, false);

        assertTrue(newService().activateByName(WS, "archived").isEmpty());
        assertFalse(saved.containsKey(WS));
    }

    @Test
    @DisplayName("按名激活：名称为 null 或空白时直接返回空，不打库")
    void blankNameShortCircuits() {
        ScenarioService svc = newService();
        assertTrue(svc.activateByName(WS, null).isEmpty());
        assertTrue(svc.activateByName(WS, "   ").isEmpty());
        assertEquals(0, rebuilds);
    }

    @Test
    @DisplayName("按名激活：名称两端空白被裁剪后仍能命中")
    void nameIsTrimmed() {
        seed("code-review", 5L, true);

        assertTrue(newService().activateByName(WS, "  code-review  ").isPresent());
        assertEquals(5L, saved.get(WS));
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
