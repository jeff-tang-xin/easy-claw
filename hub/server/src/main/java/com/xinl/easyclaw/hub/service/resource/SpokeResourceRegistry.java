package com.xinl.easyclaw.hub.service.resource;

import com.xinl.easyclaw.hub.common.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 资源类型注册表（V30）：启动时收集全部 {@link SpokeResourceCatalog} 实现 bean，
 * 通用下发端点按路径 {type} 路由。新增资源类型 = 新增一个实现 bean，本类与端点零改动。
 */
@Component
public class SpokeResourceRegistry {

    private final Map<String, SpokeResourceCatalog> catalogs = new LinkedHashMap<>();

    public SpokeResourceRegistry(List<SpokeResourceCatalog> beans) {
        for (SpokeResourceCatalog catalog : beans) {
            String type = catalog.type();
            SpokeResourceCatalog prev = catalogs.putIfAbsent(type, catalog);
            if (prev != null) {
                throw new IllegalStateException("资源类型重复注册: " + type);
            }
        }
    }

    /** 按类型取目录服务；未注册类型 404（防路径枚举）。 */
    public SpokeResourceCatalog require(String type) {
        SpokeResourceCatalog catalog = catalogs.get(type);
        if (catalog == null) {
            throw ApiException.notFound("未知资源类型: " + type);
        }
        return catalog;
    }
}
