package com.xinl.easyclaw.hub.contract.appkey;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设置 appkey 的逻辑模型别名 {@code hub_cloud} 默认路由：把 spoke 恒定发送的 {@code hub_cloud}
 * 解析到指定 provider 的指定真实模型。
 * <p>{@code providerId} 必填；{@code modelName} 必填且为 provider 声明清单内的具体模型
 * （不允许“全部模型”——别名必须唯一落到一个真实模型）。服务端另校验 provider active、
 * 对当前 org 可见，且该 appkey 已绑定该 provider（路由不得越过绑定框定的可用模型面）。
 */
public record CloudRouteRequest(
        @NotNull Long providerId,
        @NotNull @Size(min = 1, max = 64) String modelName) {
}
