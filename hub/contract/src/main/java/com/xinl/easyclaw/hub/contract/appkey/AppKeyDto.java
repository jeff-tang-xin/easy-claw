package com.xinl.easyclaw.hub.contract.appkey;

import java.time.LocalDateTime;
import java.util.List;

/**
 * appkey 视图：只暴露展示前缀 keyPrefix，明文 key 永不回显（仅创建时经 AppKeyCreatedResponse 返回一次）。
 */
public record AppKeyDto(Long id, Long orgId, String name, String keyPrefix, String status, Long createdBy,
                        LocalDateTime createdAt, LocalDateTime lastUsedAt, LocalDateTime revokedAt,
                        List<AppKeyBindingDto> bindings) {
}
