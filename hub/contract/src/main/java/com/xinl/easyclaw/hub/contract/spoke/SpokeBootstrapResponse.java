package com.xinl.easyclaw.hub.contract.spoke;

import java.util.List;

/**
 * spoke 引导响应（GET /api/spoke/bootstrap）：spoke 持 appkey 换取自身完整配置快照——
 * 身份（appKey）、归属组织（org）、生效菜单树（menus）、生效功能开关（flags）、生效工具（tools）、
 * 可用模型面（providers，按 appkey 绑定展开，绝不含真实 key）与服务权限（permissions）。
 * 菜单/开关/工具的生效语义 = 平台 enabled AND 组织 enabled/visible（hub 唯一裁决口径）。
 */
public record SpokeBootstrapResponse(
        SpokeAppKeyInfo appKey,
        SpokeOrgInfo org,
        List<SpokeMenuNode> menus,
        List<SpokeFlagInfo> flags,
        List<SpokeToolInfo> tools,
        List<SpokeProviderInfo> providers,
        List<String> permissions) {

    /** appkey 身份信息（展示与审计定位用，不含认证材料）。 */
    public record SpokeAppKeyInfo(Long id, String name, String keyPrefix, Long orgId) {
    }

    /** 归属组织。 */
    public record SpokeOrgInfo(Long id, String name, String slug) {
    }

    /**
     * 一个可用 provider 的模型面：models 为绑定展开后的可调用模型清单
     * （model_name='' 的绑定展开为该 provider 全部声明模型，同 provider 多行绑定合并去重）。
     * 不下发 baseUrl 与真实 key——转发一律经 hub 网关，spoke 无需直连。
     */
    public record SpokeProviderInfo(Long id, String slug, String name, String apiType, List<String> models) {
    }
}
