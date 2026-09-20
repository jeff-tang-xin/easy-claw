package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.SpokePermissions;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeAppKeyInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeOrgInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeBootstrapResponse.SpokeProviderInfo;
import com.xinl.easyclaw.hub.contract.spoke.SpokeMenuNode;
import com.xinl.easyclaw.hub.contract.spoke.SpokeWorkspaceInfo;
import com.xinl.easyclaw.hub.entity.AppKeyProviderBindingEntity;
import com.xinl.easyclaw.hub.entity.LlmProviderEntity;
import com.xinl.easyclaw.hub.entity.OrganizationEntity;
import com.xinl.easyclaw.hub.entity.WorkspaceEntity;
import com.xinl.easyclaw.hub.repository.AppKeyProviderBindingRepository;
import com.xinl.easyclaw.hub.repository.LlmProviderRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.WorkspaceRepository;
import com.xinl.easyclaw.hub.security.AppKeyContext;
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
    private final WorkspaceRepository workspaces;
    private final MenuService menuService;

    public SpokeService(OrganizationRepository orgs, AppKeyProviderBindingRepository bindings,
                        LlmProviderRepository providers, WorkspaceRepository workspaces, MenuService menuService) {
        this.orgs = orgs;
        this.bindings = bindings;
        this.providers = providers;
        this.workspaces = workspaces;
        this.menuService = menuService;
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
                providerInfos,
                SpokePermissions.ALL);
    }

    /**
     * 下发组织下的工作区及其公共菜单树（GET /api/spoke/workspaces）：仅 active 工作区；
     * 菜单与组织无关、对所有 spoke 通用，hub 只下发配置不做 per-spoke 控制，可见性由 spoke 本地裁剪。
     */
    @Transactional(readOnly = true)
    public List<SpokeWorkspaceInfo> distributeWorkspaces(AppKeyContext ctx) {
        List<SpokeWorkspaceInfo> out = new ArrayList<>();
        for (WorkspaceEntity w : workspaces.findByOrgIdAndStatus(ctx.orgId(), "active")) {
            out.add(toWorkspaceInfo(w));
        }
        return out;
    }

    /** 下发单个工作区的菜单树（GET /api/spoke/workspaces/{id}/menu）：非本组织工作区→404，避免跨组织探测。 */
    @Transactional(readOnly = true)
    public List<SpokeMenuNode> distributeMenu(AppKeyContext ctx, Long workspaceId) {
        WorkspaceEntity w = workspaces.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound("工作区不存在"));
        if (!w.getOrgId().equals(ctx.orgId())) {
            throw ApiException.notFound("工作区不存在");
        }
        return menuService.buildTree(workspaceId);
    }

    private SpokeWorkspaceInfo toWorkspaceInfo(WorkspaceEntity w) {
        return new SpokeWorkspaceInfo(w.getId(), w.getProjectId(), w.getOrgId(), w.getName(),
                menuService.buildTree(w.getId()));
    }
}
