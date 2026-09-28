package com.xinl.easyclaw.hub.contract.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建 provider：真实 api key 仅在请求中传输，服务端 AES-GCM 加密后落库，任何响应不回明文。
 * orgId 省略/null = 平台共享池（仅 platformAdmin 可建）；否则归属指定组织（owner/admin 可建）。
 * apiType 省略默认 openai（OpenAI 兼容协议）；anthropic = Anthropic 原生协议，A1 网关转发按此分流。
 * models 为可用模型清单（空/省略 = 未配置），服务端以逗号分隔串落库。
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 64) String slug,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 255) String baseUrl,
        @NotBlank @Size(max = 255) String apiKey,
        @Size(max = 100) List<@Size(max = 64) String> models,
        @Size(max = 255) String remark,
        Long orgId,
        @Size(max = 20) String apiType) {
}
