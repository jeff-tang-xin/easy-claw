package com.xinl.easyclaw.hub.contract.appkey;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 全量替换 appkey 的 provider 绑定：bindings 为 null/空 = 清空全部绑定。
 */
public record UpdateBindingsRequest(@Valid List<BindingRequest> bindings) {
}
