package com.xinl.easyclaw.hub.contract.appkey;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 组织 owner/admin 颁发 appkey：可随创建一并声明 provider 绑定（可空 = 暂不绑定，后续再配）。
 */
public record CreateAppKeyRequest(
        @NotBlank @Size(max = 64) String name,
        @Valid @Size(max = 20) List<BindingRequest> bindings) {
}
