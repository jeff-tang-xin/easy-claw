package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.SpokePermissions;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardAppendRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardArchiveRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardBookInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBlackboardEntryInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeAppKeyInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeOrgInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeProviderInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeFlagInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntry;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeEntryInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeKnowledgeUpsertRequest;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.contract.spoke.SpokeOpsServer;
import com.xinl.easyclaw.hub.contract.spoke.SpokeProjectInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeShellCommand;
import com.xinl.easyclaw.hub.contract.spoke.SpokeToolInfo;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.entity.OrganizationEntity;
import com.xinl.easyclaw.hub.entity.ProjectEntity;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.ProjectRepository;
import com.xinl.easyclaw.hub.repository.WorkspaceRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import com.xinl.easyclaw.hub.service.blackboard.WorkspaceBlackboardService;
import com.xinl.easyclaw.hub.service.knowledge.WorkspaceKnowledgeService;
import com.xinl.easyclaw.hub.service.ops.OpsServerService;
import com.xinl.easyclaw.hub.service.ops.ShellCommandService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spoke 数据面服务：以 appkey 上下文为唯一身份来源，组装 spoke 的配置与权限快照。
 */
@Service
public class SpokeService {

    private final OrganizationRepository orgs;
    private final AppKeyProviderBindingRepository bindings;
    private final LlmProviderRepository providers;
    private final ProjectRepository projects;
    private final WorkspaceRepository workspaces;
    private final MenuService menuService;
    private final FeatureFlagService featureFlagService;
    private final ToolCatalogService toolCatalogService;
    private final OpsServerService opsServerService;
    private final ShellCommandService shellCommandService;
    private final WorkspaceKnowledgeService workspaceKnowledgeService;
    private final WorkspaceBlackboardService workspaceBlackboardService;

    public SpokeService(OrganizationRepository orgs, AppKeyProviderBindingRepository bindings,
                        LlmProviderRepository providers, ProjectRepository projects,
                        WorkspaceRepository workspaces, MenuService menuService,
                        FeatureFlagService featureFlagService, ToolCatalogService toolCatalogService,
                        OpsServerService opsServerService, ShellCommandService shellCommandService,
                        WorkspaceKnowledgeService workspaceKnowledgeService,
                        WorkspaceBlackboardService workspaceBlackboardService) {
        this.orgs = orgs;
        this.bindings = bindings;
        this.providers = providers;
        this.projects = projects;
        this.workspaces = workspaces;
        this.menuService = menuService;
        this.featureFlagService = featureFlagService;
        this.toolCatalogService = toolCatalogService;
        this.opsServerService = opsServerService;
        this.shellCommandService = shellCommandService;
        this.workspaceKnowledgeService = workspaceKnowledgeService;
        this.workspaceBlackboardService = workspaceBlackboardService;
    }

    /**
     * bootstrap：spoke 持 appkey 换取自身配置快照。
     * providers 按 appkey 绑定展开：仅下发存在且 active 的 provider（disabled/已删除的绑定行跳过，
     * 与网关路由口径一致——路由同样只认 active）；model_name='' 展开为 provider 全部声明模型，
     * 同 provider 多行绑定合并去重且保序。
     */
    @Transactional(readOnly = true)
    public SpokeBootstrapResponse bootstrap(AppKeyContext ctx) {
        OrganizationEntity org = orgs.findById(ctx.orgId())
                .orElseThrow(() -> ApiException.notFound("appkey 归属组织不存在"));
        List<AppKeyProviderBindingEntity> bs = bindings.findByAppKeyId(ctx.appKeyId());
        Map<Long, LlmProviderEntity> providerMap = bs.isEmpty() ? Map.of()
                : providers.findAllById(bs.stream().map(AppKeyProviderBindingEntity::getProviderId).toList())
                        .stream().collect(Collectors.toMap(LlmProviderEntity::getId, Function.identity()));
        Map<Long, Set<String>> modelsByProvider = new LinkedHashMap<>();
        for (AppKeyProviderBindingEntity b : bs) {
            LlmProviderEntity p = providerMap.get(b.getProviderId());
            if (p == null || !"active".equals(p.getStatus())) {
                continue;
            }
            Set<String> models = modelsByProvider.computeIfAbsent(p.getId(), k -> new LinkedHashSet<>());
            if (b.getModelName() == null || b.getModelName().isEmpty()) {
                models.addAll(ProviderService.parseModels(p.getModels()));
            } else {
                models.add(b.getModelName());
            }
        }
        List<SpokeProviderInfo> providerInfos = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> e : modelsByProvider.entrySet()) {
            LlmProviderEntity p = providerMap.get(e.getKey());
            providerInfos.add(new SpokeProviderInfo(p.getId(), p.getSlug(), p.getName(), p.getApiType(),
                    List.copyOf(e.getValue())));
        }
        return new SpokeBootstrapResponse(
                new SpokeAppKeyInfo(ctx.appKeyId(), ctx.name(), ctx.keyPrefix(), ctx.orgId()),
                new SpokeOrgInfo(org.getId(), org.getName(), org.getSlug()),
                menuService.buildTree(org.getId()),
                featureFlagService.effectiveFlags(org.getId()),
                toolCatalogService.effectiveTools(org.getId()),
                providerInfos,
                SpokePermissions.ALL);
    }

    /**
     * 下发本组织的公共菜单树（GET /api/spoke/menus）：菜单为平台级目录，按该组织生效态过滤
     * （平台 enabled AND 组织 visible）；hub 只下发配置不做 per-spoke 控制，
     * 可见性由 spoke 本地裁剪；强制仍由 hub API 鉴权兜底。
     */
    @Transactional(readOnly = true)
    public List<SpokeMenuNode> distributeMenu(AppKeyContext ctx) {
        return menuService.buildTree(ctx.orgId());
    }

    /** 生效功能开关（GET /api/spoke/feature-flags）：仅平台 enabled 项，enabled=平台 AND 组织。 */
    @Transactional(readOnly = true)
    public List<SpokeFlagInfo> distributeFlags(AppKeyContext ctx) {
        return featureFlagService.effectiveFlags(ctx.orgId());
    }

    /** 生效工具（GET /api/spoke/tools）：仅平台 enabled 项，enabled=平台 AND 组织。 */
    @Transactional(readOnly = true)
    public List<SpokeToolInfo> distributeTools(AppKeyContext ctx) {
        return toolCatalogService.effectiveTools(ctx.orgId());
    }

    /**
     * 运维服务器目录下发（GET /api/spoke/ops-servers）：仅启用项，按 sort_order,id 保序；
     * 密码解密后随目录下发（V19 临时运维场景），未设置密码项为 null。
     * V18 过滤口径：归属本组织 + 可选按绑定项目过滤（projectId 非空时）+ 当前 appkey 用户有未过期授权。
     */
    @Transactional(readOnly = true)
    public List<SpokeOpsServer> distributeOpsServers(AppKeyContext ctx, Long projectId) {
        return opsServerService.listEnabledForSpoke(ctx, projectId);
    }

    /** Shell 命令白名单下发（GET /api/spoke/shell-commands）：仅启用项，按 sort_order,id 保序。 */
    @Transactional(readOnly = true)
    public List<SpokeShellCommand> distributeShellCommands(AppKeyContext ctx) {
        return shellCommandService.listEnabledForSpoke();
    }

    /** 本组织项目清单（GET /api/spoke/projects）：仅 active，按 id 升序；供 spoke 侧工作区绑定选择。 */
    @Transactional(readOnly = true)
    public List<SpokeProjectInfo> orgProjects(AppKeyContext ctx) {
        return projects.findByOrgIdAndStatusOrderByIdAsc(ctx.orgId(), "active").stream()
                .map(p -> new SpokeProjectInfo(p.getId(), p.getName(), p.getSlug()))
                .toList();
    }

    /** 项目知识库条目清单（GET /api/spoke/knowledge/entries）：按 topic 升序（V23 起按 projectId 查 knowledge_items）。 */
    @Transactional(readOnly = true)
    public List<SpokeKnowledgeEntryInfo> knowledgeEntries(AppKeyContext ctx, Long projectId) {
        return workspaceKnowledgeService.entries(ctx, projectId);
    }

    /** 项目知识库单条内容（GET /api/spoke/knowledge/entry）。 */
    @Transactional
    public SpokeKnowledgeEntry knowledgeEntry(AppKeyContext ctx, Long projectId, String topic) {
        return workspaceKnowledgeService.entry(ctx, projectId, topic);
    }

    /** 项目知识库 upsert（POST /api/spoke/knowledge/entries）：(projectId, topic) 存在即整体覆盖（后写赢）。 */
    @Transactional
    public SpokeKnowledgeEntryInfo knowledgeUpsert(AppKeyContext ctx, SpokeKnowledgeUpsertRequest req) {
        return workspaceKnowledgeService.upsert(ctx, req);
    }

    /** 工作区黑板追加（POST /api/spoke/blackboard/entries）：V24 统一落 blackboard_entries。 */
    @Transactional
    public SpokeBlackboardEntryInfo blackboardAppend(AppKeyContext ctx, SpokeBlackboardAppendRequest req) {
        return workspaceBlackboardService.append(ctx, req);
    }

    /** 工作区黑板归档整本（POST /api/spoke/blackboard/archive）：仅归档 source=workspace 的活跃条目。 */
    @Transactional
    public void blackboardArchive(AppKeyContext ctx, SpokeBlackboardArchiveRequest req) {
        workspaceBlackboardService.archive(ctx, req);
    }

    /** 黑板本清单（GET /api/spoke/blackboard/books）：按项目读（跨 source，人类记录 Agent 可见）。 */
    @Transactional(readOnly = true)
    public List<SpokeBlackboardBookInfo> blackboardBooks(AppKeyContext ctx, Long projectId) {
        return workspaceBlackboardService.books(ctx, projectId);
    }

    /** 黑板某本条目（GET /api/spoke/blackboard/entries）：按项目读，seq 动态编号。 */
    @Transactional(readOnly = true)
    public List<SpokeBlackboardEntryInfo> blackboardEntries(AppKeyContext ctx, Long projectId, String bookKey) {
        return workspaceBlackboardService.entries(ctx, projectId, bookKey);
    }
}
