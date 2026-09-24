package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardAppendRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardArchiveRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardBookInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardEntryInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse;
import com.xinl.easyclaw.hub.contract.spoke.SpokeFlagInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntry;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntryInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeUpsertRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsCommandLogReport;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsServer;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsServerAuthorizeCheck;
import com.xinl.easyclaw.hub.contract.spoke.SpokeProjectInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeShellCommand;
import com.xinl.easyclaw.hub.contract.spoke.SpokeToolInfo;
import com.xinl.easyclaw.hub.security.AppKeyContextHolder;
import com.xinl.easyclaw.hub.service.SpokeService;
import com.xinl.easyclaw.hub.service.ops.OpsCommandLogService;
import com.xinl.easyclaw.hub.service.ops.OpsServerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * spoke 数据面端点（/api/spoke/**，appkey 认证，见 AppKeyAuthFilter）：
 * 来自子系统的请求统一入口，一切配置与权限信息基于 appkey 上下文框定。
 */
@RestController
public class SpokeController {

    private final SpokeService spokeService;
    private final OpsCommandLogService commandLogs;
    private final OpsServerService opsServers;

    public SpokeController(SpokeService spokeService, OpsCommandLogService commandLogs,
                           OpsServerService opsServers) {
        this.spokeService = spokeService;
        this.commandLogs = commandLogs;
        this.opsServers = opsServers;
    }

    /** bootstrap：spoke 启动/刷新时拉取自身配置快照（身份、组织、可用模型面、服务权限）。 */
    @GetMapping("/api/spoke/bootstrap")
    public SpokeBootstrapResponse bootstrap() {
        return spokeService.bootstrap(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效菜单树（平台目录 × 组织可见性，仅生效项；spoke 侧消费在后续阶段）。 */
    @GetMapping("/api/spoke/menus")
    public List<SpokeMenuNode> menus() {
        return spokeService.distributeMenu(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效功能开关（仅平台 enabled 项，enabled=平台 AND 组织）。 */
    @GetMapping("/api/spoke/feature-flags")
    public List<SpokeFlagInfo> featureFlags() {
        return spokeService.distributeFlags(AppKeyContextHolder.require());
    }

    /** 下发本组织的生效工具（仅平台 enabled 项，enabled=平台 AND 组织）。 */
    @GetMapping("/api/spoke/tools")
    public List<SpokeToolInfo> tools() {
        return spokeService.distributeTools(AppKeyContextHolder.require());
    }

    /** 下发运维服务器目录（仅启用+本组织+当前用户有未过期授权，按 sort_order,id 保序；密码解密后随目录下发）。 */
    @GetMapping("/api/spoke/ops-servers")
    public List<SpokeOpsServer> opsServers(@RequestParam(value = "projectId", required = false) Long projectId) {
        return spokeService.distributeOpsServers(AppKeyContextHolder.require(), projectId);
    }

    /** 下发 Shell 命令白名单（仅启用项，按 sort_order,id 保序）。 */
    @GetMapping("/api/spoke/shell-commands")
    public List<SpokeShellCommand> shellCommands() {
        return spokeService.distributeShellCommands(AppKeyContextHolder.require());
    }

    // ---- 运维命令记录上报（V25）----

    /** 运维命令记录批量上报（追加型审计日志；单批 ≤200 条，source 仅 ai|user，org/operator 取 appkey 上下文）。 */
    @PostMapping("/api/spoke/ops-command-logs")
    public void reportOpsCommandLogs(@Valid @RequestBody SpokeOpsCommandLogReport req) {
        commandLogs.report(AppKeyContextHolder.require(), req.logs());
    }

    /** 活跃连接授权校验（spoke 定时批量调用）：serverKey → 是否仍有未过期授权；未知 serverKey 一律 false。 */
    @PostMapping("/api/spoke/ops-servers/authorize-check")
    public Map<String, Boolean> authorizeCheck(@Valid @RequestBody SpokeOpsServerAuthorizeCheck req) {
        return opsServers.authorizeCheck(AppKeyContextHolder.require(), req.serverKeys());
    }

    // ---- 项目与工作区绑定（V18）----

    /** 本组织项目清单（仅 active，按 id 升序）：供 spoke 侧工作区绑定选择。 */
    @GetMapping("/api/spoke/projects")
    public List<SpokeProjectInfo> projects() {
        return spokeService.orgProjects(AppKeyContextHolder.require());
    }

    // ---- 工作区知识库云同步（V23 起统一落项目知识库 knowledge_items，按 projectId 读写） ----

    /** 项目知识库条目清单（按 topic 升序；lastModified/fileSize 供增量同步判断）。 */
    @GetMapping("/api/spoke/knowledge/entries")
    public List<SpokeKnowledgeEntryInfo> knowledgeEntries(@RequestParam("projectId") Long projectId) {
        return spokeService.knowledgeEntries(AppKeyContextHolder.require(), projectId);
    }

    /** 项目知识库单条内容。 */
    @GetMapping("/api/spoke/knowledge/entry")
    public SpokeKnowledgeEntry knowledgeEntry(@RequestParam("projectId") Long projectId,
                                              @RequestParam("topic") String topic) {
        return spokeService.knowledgeEntry(AppKeyContextHolder.require(), projectId, topic);
    }

    /** 项目知识库 upsert：(projectId, topic) 存在即整体覆盖（后写赢）。 */
    @PostMapping("/api/spoke/knowledge/entries")
    public SpokeKnowledgeEntryInfo knowledgeUpsert(@Valid @RequestBody SpokeKnowledgeUpsertRequest req) {
        return spokeService.knowledgeUpsert(AppKeyContextHolder.require(), req);
    }

    // ---- 工作区黑板云同步（V24 统一落 blackboard_entries，按 projectId 读写） ----

    /** 黑板追加一条：落 blackboard_entries（source=workspace，author=0 占位）；归档本拒绝。 */
    @PostMapping("/api/spoke/blackboard/entries")
    public SpokeBlackboardEntryInfo blackboardAppend(@Valid @RequestBody SpokeBlackboardAppendRequest req) {
        return spokeService.blackboardAppend(AppKeyContextHolder.require(), req);
    }

    /** 黑板归档整本：该本 source=workspace 的活跃条目全部转 archived（平台条目不受影响）。 */
    @PostMapping("/api/spoke/blackboard/archive")
    public void blackboardArchive(@Valid @RequestBody SpokeBlackboardArchiveRequest req) {
        spokeService.blackboardArchive(AppKeyContextHolder.require(), req);
    }

    /** 黑板本清单（按 bookKey 升序；archivedAt 非空即归档本，虚拟键 &lt;key&gt;.archived-&lt;ts&gt;）。 */
    @GetMapping("/api/spoke/blackboard/books")
    public List<SpokeBlackboardBookInfo> blackboardBooks(@RequestParam("projectId") Long projectId) {
        return spokeService.blackboardBooks(AppKeyContextHolder.require(), projectId);
    }

    /** 黑板某本条目（按 id 升序，seq 动态编号；归档键含 .archived- 自动映射 archived 状态）。 */
    @GetMapping("/api/spoke/blackboard/entries")
    public List<SpokeBlackboardEntryInfo> blackboardEntries(@RequestParam("projectId") Long projectId,
                                                            @RequestParam("bookKey") String bookKey) {
        return spokeService.blackboardEntries(AppKeyContextHolder.require(), projectId, bookKey);
    }
}
