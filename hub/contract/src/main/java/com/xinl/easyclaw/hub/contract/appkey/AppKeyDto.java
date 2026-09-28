package com.xinl.easyclaw.hub.contract.appkey;

import java.time.LocalDateTime;
import java.util.List;

/**
 * appkey 视图：只暴露展示前缀 keyPrefix，明文 key 永不回显（仅创建时经 AppKeyCreatedResponse 返回一次）。
 * cloudRoute 为逻辑模型别名 {@code hub_cloud} 的默认路由落点（未配置时为 null），
 * 复用绑定视图结构（providerId/slug/name/modelName）。
 */
public record AppKeyDto(Long id, Long orgId, String name, String keyPrefix, String status, Long createdBy,
                        LocalDateTime createdAt, LocalDateTime lastUsedAt, LocalDateTime revokedAt,
                        List<AppKeyBindingDto> bindings, AppKeyBindingDto cloudRoute) {
}
