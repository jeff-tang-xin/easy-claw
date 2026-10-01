package com.xinl.easyclaw.hub.service.resource;

import com.xinl.easyclaw.hub.contract.spoke.SpokeResourceItem;
import com.xinl.easyclaw.hub.security.AppKeyContext;
import java.util.List;
import java.util.Map;

/**
 * 通用资源下发目录接口（资源下放能力，V30）：各资源类型实现本接口并注册为 Spring bean，
 * {@link SpokeResourceRegistry} 启动时自动收集，{@code SpokeResourceController} 按路径
 * {@code /api/spoke/resources/{type}} 路由。新增资源类型 = 实现本接口 + 建目录表，管道零改动。
 * 过滤口径全类型一致：enabled + 归属当前 appkey 组织 + 可选按项目过滤 +
 * 当前用户存在未过期授权（resource_grants）；密码解密随目录下发（仅服务端持有）。
 */
public interface SpokeResourceCatalog {

    /** 资源类型标识（= 下发端点路径段，如 db-connection）。 */
    String type();

    /**
     * spoke 下发清单：仅启用 + 归属当前组织 + 项目过滤 + 当前用户未过期授权，按 sort_order,id 保序。
     *
     * @param projectId 工作区绑定项目（null = 不过滤；条目 projectId<=0 = 不限定项目对所有项目可见）
     */
    List<SpokeResourceItem> listEnabledForSpoke(AppKeyContext ctx, Long projectId);

    /**
     * 活跃连接授权校验：serverKey → 是否仍有未过期授权；未知 serverKey 一律 false。
     * 实现方自行按 serverKey 定位目录行（不回查授权表以外的服务）。
     */
    Map<String, Boolean> authorizeCheck(AppKeyContext ctx, List<String> serverKeys);
}
