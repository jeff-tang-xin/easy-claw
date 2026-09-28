package com.xinl.easyclaw.hub.contract.appkey;

/**
 * appkey 的单条 provider 绑定。modelName 空串 = 该 provider 全部模型；
 * provider 被删除时 providerSlug/providerName 为 null（绑定记录保留，不阻断展示）。
 */
public record AppKeyBindingDto(Long providerId, String providerSlug, String providerName, String modelName) {
}
