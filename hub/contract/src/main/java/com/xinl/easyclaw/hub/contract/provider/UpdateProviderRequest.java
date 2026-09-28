package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 更新 provider：字段全可选（null/空白 = 不改）；apiKey 给了才换（重新加密落库）；
 * models 传空列表可清空模型清单；status 仅 active|disabled、apiType 仅 openai|anthropic（服务端校验）。
 * 组织归属（org_id）创建后不可改。
 */
public record UpdateProviderRequest(
        @Size(max = 64) String name,
        @Size(max = 255) String baseUrl,
        @Size(max = 255) String apiKey,
        @Size(max = 100) List<@Size(max = 64) String> models,
        @Size(max = 20) String status,
        @Size(max = 255) String remark,
        @Size(max = 20) String apiType) {
}
