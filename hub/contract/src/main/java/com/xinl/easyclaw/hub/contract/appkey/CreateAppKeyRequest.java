package com.xinl.easyclaw.hub.contract.appkey;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 组织 owner/admin 颁发 appkey：可随创建一并声明 provider 绑定（可空 = 暂不绑定，后续再配），
 * 也可一并声明逻辑模型别名 {@code hub_cloud} 的默认路由（可空 = 暂不配置）。
 */
public record CreateAppKeyRequest(
        @NotBlank @Size(max = 64) String name,
        @Valid @Size(max = 20) List<BindingRequest> bindings,
        @Valid CloudRouteRequest cloudRoute) {

    /** 兼容未配置 hub_cloud 路由的旧调用点（等价于 cloudRoute=null），避免 record 增项破坏既有调用方编译。 */
    public CreateAppKeyRequest(String name, List<BindingRequest> bindings) {
        this(name, bindings, null);
    }
}
