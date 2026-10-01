package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.db.CreateDbConnectionGrantRequest;
import com.xinl.easyclaw.hub.contract.db.CreateDbConnectionRequest;
import com.xinl.easyclaw.hub.contract.db.DbConnectionDto;
import com.xinl.easyclaw.hub.contract.db.DbConnectionGrantDto;
import com.xinl.easyclaw.hub.contract.db.DbQueryLogDto;
import com.xinl.easyclaw.hub.contract.db.UpdateDbConnectionRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.PlatformAdminGuard;
import com.xinl.easyclaw.hub.service.db.DbConnectionService;
import com.xinl.easyclaw.hub.service.db.DbQueryLogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库连接平台目录端点（platformAdmin 专属，PlatformAdminGuard 强制，V30）：
 * 数据库连接目录（db_connections）CRUD + 授权（resource_grants）+ 查询审计（db_query_logs）分页。
 * 目录为平台级资源，spoke 侧只有只读下发端点（SpokeResourceController 通用管道），与此处互不重叠。
 */
@RestController
public class DbCatalogController {

    private final DbConnectionService connections;
    private final DbQueryLogService queryLogs;
    private final PlatformAdminGuard platformAdminGuard;

    public DbCatalogController(DbConnectionService connections, DbQueryLogService queryLogs,
                               PlatformAdminGuard platformAdminGuard) {
        this.connections = connections;
        this.queryLogs = queryLogs;
        this.platformAdminGuard = platformAdminGuard;
    }

    // ---- 数据库连接目录 ----

    @GetMapping("/api/platform/db-connections")
    public List<DbConnectionDto> listDbConnections() {
        return connections.listCatalog(CurrentUserHolder.requireUserId());
    }

    @PostMapping("/api/platform/db-connections")
    public DbConnectionDto createDbConnection(@Valid @RequestBody CreateDbConnectionRequest req) {
        return connections.createCatalogItem(CurrentUserHolder.requireUserId(), req);
    }

    @PutMapping("/api/platform/db-connections/{id}")
    public DbConnectionDto updateDbConnection(@PathVariable("id") Long id,
                                              @Valid @RequestBody UpdateDbConnectionRequest req) {
        return connections.updateCatalogItem(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/api/platform/db-connections/{id}")
    public void deleteDbConnection(@PathVariable("id") Long id) {
        connections.deleteCatalogItem(CurrentUserHolder.requireUserId(), id);
    }

    // ---- 授权（通用 resource_grants，type=db-connection）----

    @GetMapping("/api/platform/db-connections/{id}/grants")
    public List<DbConnectionGrantDto> listDbConnectionGrants(@PathVariable("id") Long id) {
        return connections.listGrants(CurrentUserHolder.requireUserId(), id);
    }

    @PostMapping("/api/platform/db-connections/{id}/grants")
    public DbConnectionGrantDto grantDbConnection(@PathVariable("id") Long id,
                                                  @Valid @RequestBody CreateDbConnectionGrantRequest req) {
        return connections.grant(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/api/platform/db-connections/grants/{grantId}")
    public void revokeDbConnectionGrant(@PathVariable("grantId") Long grantId) {
        connections.revokeGrant(CurrentUserHolder.requireUserId(), grantId);
    }

    // ---- 查询审计 ----

    @GetMapping("/api/platform/db-connections/{id}/query-logs")
    public DbQueryLogDto.PageResponse dbQueryLogs(@PathVariable("id") Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return queryLogs.listByConnection(CurrentUserHolder.requireUserId(), id, page, size);
    }
}
