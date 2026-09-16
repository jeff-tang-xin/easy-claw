package com.xinl.easyclaw.hub.security;

/**
 * spoke 数据面 appkey 调用上下文：一次 appkey 认证的解析结果（appkey 为组织级凭据，userId 归属其创建人）。
 * 所有来自子系统（spoke）的请求一律以此身份框定配置信息与权限信息，由 {@link AppKeyAuthFilter} 装填。
 */
public record AppKeyContext(Long appKeyId, String name, String keyPrefix, Long orgId, Long userId) {
}
