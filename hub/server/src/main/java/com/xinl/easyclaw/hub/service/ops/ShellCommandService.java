package com.xinl.easyclaw.hub.service.ops;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.ops.CreateShellCommandRequest;
import com.xinl.easyclaw.hub.contract.ops.ShellCommandDto;
import com.xinl.easyclaw.hub.contract.ops.UpdateShellCommandRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeShellCommand;
import com.xinl.easyclaw.hub.entity.ShellCommandEntity;
import com.xinl.easyclaw.hub.repository.ShellCommandRepository;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shell 命令白名单服务（shell_commands，V17）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * cmd 全局唯一、创建后不可改；subcommands 接口层收列表、库内存逗号分隔串（空 = 整命令放行）。
 */
@Service
public class ShellCommandService {

    private final ShellCommandRepository repo;
    private final PlatformAdminGuard guard;
    private final AuditService auditService;

    public ShellCommandService(ShellCommandRepository repo, PlatformAdminGuard guard, AuditService auditService) {
        this.repo = repo;
        this.guard = guard;
        this.auditService = auditService;
    }

    /** 目录清单（platformAdmin），按 sort_order,id 保序。 */
    public List<ShellCommandDto> listCatalog(Long requesterId) {
        guard.require(requesterId);
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(ShellCommandService::toDto).toList();
    }

    /** 新增白名单项（platformAdmin）：cmd 全局唯一（409），subcommands 可空（空 = 整命令放行）。 */
    @Transactional
    public ShellCommandDto createCatalogItem(Long requesterId, CreateShellCommandRequest req) {
        guard.require(requesterId);
        String cmd = req.cmd().trim();
        if (repo.existsByCmd(cmd)) {
            throw ApiException.conflict("cmd 已存在");
        }
        ShellCommandEntity e = new ShellCommandEntity();
        e.setCmd(cmd);
        e.setSubcommands(joinSubcommands(req.subcommands()));
        e.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        e.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        repo.save(e);
        auditService.record(AuditModule.OPS, "create_shell_command", requesterId, null, "shell_command",
                String.valueOf(e.getId()), "cmd=" + cmd, AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 更新白名单项（platformAdmin）：cmd 创建后不可改（请求体不含该字段）；subcommands 传 null = 不改，传列表 = 整体替换。 */
    @Transactional
    public ShellCommandDto updateCatalogItem(Long requesterId, Long id, UpdateShellCommandRequest req) {
        guard.require(requesterId);
        ShellCommandEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("Shell 命令不存在"));
        if (req.subcommands() != null) {
            e.setSubcommands(joinSubcommands(req.subcommands()));
        }
        if (req.sortOrder() != null) {
            e.setSortOrder(req.sortOrder());
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        repo.save(e);
        auditService.record(AuditModule.OPS, "update_shell_command", requesterId, null, "shell_command",
                String.valueOf(e.getId()), "cmd=" + e.getCmd(), AuditModule.SUCCESS);
        return toDto(e);
    }

    /** 删除白名单项（platformAdmin）。 */
    @Transactional
    public void deleteCatalogItem(Long requesterId, Long id) {
        guard.require(requesterId);
        ShellCommandEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("Shell 命令不存在"));
        repo.delete(e);
        auditService.record(AuditModule.OPS, "delete_shell_command", requesterId, null, "shell_command",
                String.valueOf(id), "cmd=" + e.getCmd(), AuditModule.SUCCESS);
    }

    /** spoke 下发用：仅启用项，按 sort_order,id 保序；subcommands 逗号串还原为列表（空串 = 空列表 = 整命令放行）。 */
    public List<SpokeShellCommand> listEnabledForSpoke() {
        return repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(e -> new SpokeShellCommand(e.getCmd(), splitSubcommands(e.getSubcommands())))
                .toList();
    }

    /** 列表 → 逗号分隔串：去空白、去空项、去重保序；空列表落空串。 */
    private static String joinSubcommands(List<String> subcommands) {
        if (subcommands == null || subcommands.isEmpty()) {
            return "";
        }
        return subcommands.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** 逗号分隔串 → 列表：空串 = 空列表。 */
    private static List<String> splitSubcommands(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static ShellCommandDto toDto(ShellCommandEntity e) {
        return new ShellCommandDto(e.getId(), e.getCmd(), splitSubcommands(e.getSubcommands()),
                e.getSortOrder(), e.getEnabled());
    }
}
