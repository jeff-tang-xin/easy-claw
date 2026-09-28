package com.xinl.easyclaw.hub.contract.appkey;

/**
 * 创建 appkey 的响应：plainKey 仅此一次返回，服务端只存 SHA-256 hash，遗失只能重新颁发。
 */
public record AppKeyCreatedResponse(AppKeyDto appKey, String plainKey) {
}
