package com.xinl.easyclaw.hub.common;

import java.util.List;

/**
 * spoke 数据面服务权限码：appkey 认证成功后随 bootstrap 下发，spoke 据此框定可调用的服务范围。
 * 当前为服务级粒度（active appkey = 组织级全量服务权限）；未来按服务上线扩展权限码，
 * 如需 per-appkey 细粒度再为 app_keys 加 scope 列并在此过滤。
 */
public final class SpokePermissions {

    /** LLM 网关转发（/api/gateway/v1/**）。 */
    public static final String LLM_INVOKE = "llm.invoke";

    /** active appkey 当前可获得的全部权限。 */
    public static final List<String> ALL = List.of(LLM_INVOKE);

    private SpokePermissions() {
    }
}
