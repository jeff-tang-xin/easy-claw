package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerGrantRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.ops.CreateShellCommandRequest;
import com.xinl.easyclaw.hub.contract.ops.OpsCommandLogPageResponse;
import com.xinl.easyclaw.hub.contract.ops.OpsServerDto;
import com.xinl.easyclaw.hub.contract.ops.OpsServerGrantDto;
import com.xinl.easyclaw.hub.contract.ops.ShellCommandDto;
import com.xinl.easyclaw.hub.contract.ops.UpdateOpsServerRequest;
import com.xinl.easyclaw.hub.contract.ops.UpdateShellCommandRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import com.xinl.easyclaw.hub.service.ops.OpsCommandLogService;
import com.xinl.easyclaw.hub.service.ops.OpsServerService;
import com.xinl.easyclaw.hub.service.ops.ShellCommandService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维平台目录端点（/api/platform/**，platformAdmin 专属，PlatformAdminGuard 强制）：
 * 运维服务器目录（ops_servers）与 Shell 命令白名单（shell_commands）CRUD。
 * 目录为平台级资源，spoke 侧只有只读下发端点（SpokeController），与此处互不重叠。
 */
@RestController
public class OpsCatalogController {

    private final OpsServerService opsServers;
    private final ShellCommandService shellCommands;
    private final OpsCommandLogService commandLogs;
    private final PlatformAdminGuard platformAdminGuard;

    public OpsCatalogController(OpsServerService opsServers, ShellCommandService shellCommands,
                                OpsCommandLogService commandLogs, PlatformAdminGuard platformAdminGuard) {
        this.opsServers = opsServers;
        this.shellCommands = shellCommands;
        this.commandLogs = commandLogs;
        this.platformAdminGuard = platformAdminGuard;
    }

    // ---- 运维服务器目录 ----

    @GetMapping("/api/platform/ops-servers")
    public List<OpsServerDto> listOpsServers() {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return opsServers.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PostMapping("/api/platform/ops-servers")
    public OpsServerDto createOpsServer(@Valid @RequestBody CreateOpsServerRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return opsServers.createCatalogItem(userId, req);
    }

    @PutMapping("/api/platform/ops-servers/{id}")
    public OpsServerDto updateOpsServer(@PathVariable Long id, @Valid @RequestBody UpdateOpsServerRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return opsServers.updateCatalogItem(userId, id, req);
    }

    @DeleteMapping("/api/platform/ops-servers/{id}")
    public ResponseEntity<Void> deleteOpsServer(@PathVariable Long id) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        opsServers.deleteCatalogItem(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ---- 运维服务器用户时效授权（V18）----

    /** 某服务器的授权清单（按 id 保序；expired=valid_until&lt;now）。 */
    @GetMapping("/api/platform/ops-servers/{id}/grants")
    public List<OpsServerGrantDto> listOpsServerGrants(@PathVariable Long id) {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return opsServers.listGrants(CurrentUserHolder.requireUserId(), id);
    }

    /** 授权用户访问服务器：同 (serverId,userId) 已有授权即续期（valid_from/valid_until/granted_by 整体刷新）。 */
    @PostMapping("/api/platform/ops-servers/{id}/grants")
    public OpsServerGrantDto grantOpsServer(@PathVariable Long id,
                                            @Valid @RequestBody CreateOpsServerGrantRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return opsServers.grant(userId, id, req);
    }

    /** 撤销授权（行删除；过期授权由 spoke 下发过滤自然失效，不在此清理）。 */
    @DeleteMapping("/api/platform/ops-grants/{grantId}")
    public ResponseEntity<Void> revokeOpsServerGrant(@PathVariable Long grantId) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        opsServers.revokeGrant(userId, grantId);
        return ResponseEntity.noContent().build();
    }

    // ---- 运维服务器命令记录（V25，spoke 上报落库）----

    /** 某服务器的命令执行记录（executed_at 倒序分页；source 区分 ai | user，operator 为 appkey 创建人）。 */
    @GetMapping("/api/platform/ops-servers/{id}/command-logs")
    public OpsCommandLogPageResponse listOpsServerCommandLogs(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return commandLogs.listByServer(CurrentUserHolder.requireUserId(), id, page, size);
    }

    // ---- Shell 命令白名单 ----

    @GetMapping("/api/platform/shell-commands")
    public List<ShellCommandDto> listShellCommands() {
        platformAdminGuard.require(CurrentUserHolder.requireUserId());
        return shellCommands.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PostMapping("/api/platform/shell-commands")
    public ShellCommandDto createShellCommand(@Valid @RequestBody CreateShellCommandRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return shellCommands.createCatalogItem(userId, req);
    }

    @PutMapping("/api/platform/shell-commands/{id}")
    public ShellCommandDto updateShellCommand(@PathVariable Long id, @Valid @RequestBody UpdateShellCommandRequest req) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        return shellCommands.updateCatalogItem(userId, id, req);
    }

    @DeleteMapping("/api/platform/shell-commands/{id}")
    public ResponseEntity<Void> deleteShellCommand(@PathVariable Long id) {
        Long userId = CurrentUserHolder.requireUserId();
        platformAdminGuard.require(userId);
        shellCommands.deleteCatalogItem(userId, id);
        return ResponseEntity.noContent().build();
    }
}
