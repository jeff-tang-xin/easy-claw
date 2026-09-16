package com.xinl.easyclaw.hub.contract.provider;

import java.util.List;

/**
 * LLM provider 视图。orgId 为 null 表示平台共享池，否则归属对应组织（orgName 随行带出，供列表直接展示）；
 * apiType 为协议类型（openai|anthropic），A1 网关转发按此分流。
 * keyHint 为真实 api key 的尾 4 位掩码（如 "****a1b2"），永不回明文；
 * 解密失败（主密钥不符/密文损坏）时 keyHint 为 null，不影响列表展示。
 */
public record ProviderDto(Long id, String slug, String name, String baseUrl, List<String> models,
                          String status, String remark, String keyHint, Long orgId, String orgName,
                          String apiType) {
}
