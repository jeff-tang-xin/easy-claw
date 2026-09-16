package com.xinl.easyclaw.hub.contract.appkey;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 一条 appkey↔provider 绑定请求：modelName 空白 = 该 provider 全部模型；
 * 非空白时必须在 provider 声明的 models 清单内（服务端校验）。
 */
public record BindingRequest(
        @NotNull Long providerId,
        @Size(max = 64) String modelName) {
}
