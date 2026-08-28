package com.xinl.easyclaw.tool.service;

import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistryService#isRegistered(String)} 的分支行为。
 *
 * <p>回归背景：确认弹窗选「始终允许」时走 {@code ToolConfirmValidator.rejectUnknown}，
 * 判据为「该会话挂起的工具名」∪ {@code isRegistered}。而 {@code isRegistered} 原先只认
 * {@code ALL_FRAMEWORK_TOOLS} 与 DB 行；用 {@code @Tool} 注解注册的工具
 * （{@code run_skill_script} / {@code run_python} 等）两者都不在 —— 一旦挂起记录已被清理
 * （超时、重连、进程重启），用户就会收到「未知工具: [run_skill_script]」而无法授权。
 *
 * <p>关键：这里必须真正调用 {@code isRegistered}，并让假 DB <b>始终返回空</b>，
 * 才能锁住「不依赖 DB 行」这一语义。只断言静态表内容的写法无法在修复被撤掉时转红。
 */
class ToolRegistryIsRegisteredTest {

    /** 永远查不到记录的 DB —— 模拟「工具从未在管理页改过配置」这一默认状态。 */
    private static class EmptyToolService implements ToolManagementService {
        @Override public Optional<ToolDefinitionEntity> findByName(String name) {
            return Optional.empty();
        }
        @Override public ToolDefinitionEntity create(ToolDefinitionEntity tool) {
            throw new UnsupportedOperationException();
        }
        @Override public ToolDefinitionEntity update(Long id, ToolDefinitionEntity tool) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(Long id) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ToolDefinitionEntity> findById(Long id) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ToolDefinitionEntity> findAll() {
            throw new UnsupportedOperationException();
        }
        @Override public List<ToolDefinitionEntity> findEnabledTools() {
            throw new UnsupportedOperationException();
        }
        @Override public List<ToolDefinitionEntity> findByGroup(String toolGroup) {
            throw new UnsupportedOperationException();
        }
        @Override public ToolDefinitionEntity setEnabled(Long id, boolean enabled) {
            throw new UnsupportedOperationException();
        }
    }

    /** isRegistered 只依赖 toolService，其余协作者不参与，传 null 即可暴露误用。 */
    private ToolRegistryService service() {
        return new ToolRegistryService(new EmptyToolService(), null, null, null);
    }

    @Test
    void annotationRegisteredToolsAreAcceptedWithoutDbRow() {
        ToolRegistryService svc = service();
        for (String name : List.of("run_skill_script", "run_python", "analyze_code",
                "format_code", "diff_code", "list_directory", "search_files")) {
            assertTrue(svc.isRegistered(name),
                    "DB 无行时被误判为未知工具，确认弹窗「始终允许」会失败: " + name);
        }
    }

    @Test
    void frameworkToolsAreAcceptedWithoutDbRow() {
        ToolRegistryService svc = service();
        assertTrue(svc.isRegistered("read_file"));
        assertTrue(svc.isRegistered("execute"));
        assertTrue(svc.isRegistered("agent_spawn"));
    }

    @Test
    void trulyUnknownNameIsStillRejected() {
        // 修复不能退化成「一律放过」：白名单的意义在于挡住预埋的任意名字。
        ToolRegistryService svc = service();
        assertFalse(svc.isRegistered("definitely_not_a_tool_xyz"));
        assertFalse(svc.isRegistered("rm_rf_everything"));
    }

    @Test
    void blankAndNullAreRejected() {
        ToolRegistryService svc = service();
        assertFalse(svc.isRegistered(null));
        assertFalse(svc.isRegistered(""));
        assertFalse(svc.isRegistered("   "));
    }
}
